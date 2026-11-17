package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.lod.DistantTerrain;
import dev.bedrockvisuals.lod.DistantTerrainSource;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLongArray;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * The far terrain's heights, for its shadows. The shadow cascades reach 128 blocks; past them nothing cast a
 * shadow, so Distant Horizons' hills and mountains neither shaded themselves nor the near terrain (a low sun
 * behind a far ridge). A worker thread asks the distant terrain source for the top of the highest solid block
 * every {@link #CELL} blocks, nearest first, over {@link #SIZE}² cells around the camera, and the render thread
 * uploads what changed. The lighting pass marches towards the sun across the map (lighting/deferred.fsh).
 *
 * <p>The map wraps on world coordinates, like the water column map, so a cell always sits at the same texel.
 */
public final class DistantHeights {
	static final int SIZE = 1024;
	static final int CELL = 8;
	/** Rows uploaded per frame at most. */
	private static final int ROWS_PER_FRAME = 64;
	/** How far the camera moves (cells) before the worker starts again from it. */
	private static final int RECENTER = 32;

	private static final short[] heights = new short[SIZE * SIZE];
	/** One bit per row: written by the worker since the last upload. */
	private static final AtomicLongArray dirtyRows = new AtomicLongArray(SIZE / 64);
	private static volatile int centerX;
	private static volatile int centerZ;
	private static volatile int minY;
	private static volatile boolean running;
	private static volatile boolean any;
	private static volatile boolean failed;
	private static Thread worker;
	private static GpuTexture texture;
	private static GpuTextureView view;
	private static int nextUploadRow;
	private static Object lastLevel;

	private DistantHeights() {
	}

	/** Once a frame, before the lighting pass: follows the camera and uploads rows the worker filled. */
	static void update() {
		Minecraft minecraft = Minecraft.getInstance();
		DistantTerrainSource source = DistantTerrain.source().orElse(null);
		if (minecraft.level == null || source == null || failed || !DeferredRenderer.ownsDistantComposite()) {
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		centerX = Math.floorDiv((int) Math.floor(camera.x), CELL);
		centerZ = Math.floorDiv((int) Math.floor(camera.z), CELL);
		minY = minecraft.level.getMinY();
		if (minecraft.level != lastLevel) {
			// Another world or dimension: the old heights would cast its shadows here.
			lastLevel = minecraft.level;
			generation++;
			java.util.Arrays.fill(heights, (short) 0);
			for (int i = 0; i < dirtyRows.length(); i++) {
				dirtyRows.set(i, -1L);
			}
			any = false;
		}
		if (worker == null) {
			running = true;
			worker = new Thread(() -> work(source), "Bedrock Visuals distant heights");
			worker.setDaemon(true);
			worker.setPriority(Thread.MIN_PRIORITY);
			worker.start();
		}
		upload();
	}

	/** Cells along a tile's side: 64 blocks, one of Distant Horizons' data sections at its finest detail. */
	private static final int TILE = 8;
	private static final int TILES = SIZE / TILE;
	/** Per tile of the map (wrapping like it): the world tile it last read completely, else Long.MIN_VALUE. */
	private static final long[] tileRead = new long[TILES * TILES];
	/** How often the tiles already read are read again, for terrain Distant Horizons has changed since. */
	private static final long FULL_REFRESH_NANOS = 300_000_000_000L;
	/** Bumped on a new level: the worker forgets which tiles it has read. */
	private static volatile int generation;

	/**
	 * Reads the map tile by tile in rings outwards from the camera's tile, so the nearest far terrain arrives
	 * first, letting go of the source's data after each tile. A tile read completely (every cell had data) isn't
	 * read again until the camera has moved far enough for its texels to hold another one, or every five
	 * minutes. Before, the whole map (a million columns) was read again every five seconds, through one cache
	 * that kept everything it touched.
	 */
	private static void work(DistantTerrainSource source) {
		java.util.Arrays.fill(tileRead, Long.MIN_VALUE);
		int seen = generation;
		long lastFull = System.nanoTime();
		try {
			while (running) {
				if (seen != generation || System.nanoTime() - lastFull > FULL_REFRESH_NANOS) {
					seen = generation;
					lastFull = System.nanoTime();
					java.util.Arrays.fill(tileRead, Long.MIN_VALUE);
				}
				int tx = Math.floorDiv(centerX, TILE);
				int tz = Math.floorDiv(centerZ, TILE);
				boolean moved = false;
				for (int ring = 0; ring < TILES / 2 && running && !moved; ring++) {
					for (int dz = -ring; dz <= ring && !moved; dz++) {
						int step = dz == -ring || dz == ring ? 1 : 2 * ring;
						for (int dx = -ring; dx <= ring && !moved; dx += Math.max(step, 1)) {
							readTile(source, tx + dx, tz + dz);
							moved = Math.abs(Math.floorDiv(centerX, TILE) - tx) > RECENTER / TILE
									|| Math.abs(Math.floorDiv(centerZ, TILE) - tz) > RECENTER / TILE
									|| seen != generation;
						}
					}
				}
				if (!moved) {
					// Every tile in reach is read or has nothing yet: look again for terrain DH loads later.
					Thread.sleep(5000);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			BedrockVisualsClient.LOGGER.warn("[distant] couldn't read Distant Horizons' terrain heights; far terrain "
					+ "casts no shadows", e);
		} finally {
			source.releaseHeightCache();
		}
	}

	private static void readTile(DistantTerrainSource source, int tileX, int tileZ) {
		int index = Math.floorMod(tileZ, TILES) * TILES + Math.floorMod(tileX, TILES);
		long key = ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
		if (tileRead[index] == key) {
			return;
		}
		boolean complete = true;
		try {
			for (int z = 0; z < TILE; z++) {
				for (int x = 0; x < TILE; x++) {
					complete &= sample(source, tileX * TILE + x, tileZ * TILE + z);
				}
			}
		} finally {
			source.releaseHeightCache();
		}
		tileRead[index] = complete ? key : Long.MIN_VALUE;
	}

	/** Reads one cell; whether the source had data for it. */
	private static boolean sample(DistantTerrainSource source, int cellX, int cellZ) {
		int top = source.topSolidY(cellX * CELL + CELL / 2, cellZ * CELL + CELL / 2);
		int value = top == DistantTerrainSource.NO_HEIGHT ? 0 : Math.max(1, Math.min(65535, top - minY));
		int tx = Math.floorMod(cellX, SIZE);
		int tz = Math.floorMod(cellZ, SIZE);
		int index = tz * SIZE + tx;
		if (heights[index] != (short) value) {
			heights[index] = (short) value;
			VERSIONS.incrementAndGet(versionIndex(Math.floorDiv(cellX * CELL, 16), Math.floorDiv(cellZ * CELL, 16)));
			dirtyRows.getAndUpdate(tz >> 6, bits -> bits | (1L << (tz & 63)));
			if (value != 0) {
				any = true;
			}
		}
		return value != 0;
	}

	private static void upload() {
		GpuDevice device = RenderSystem.getDevice();
		if (texture == null) {
			texture = device.createTexture(() -> "Bedrock Visuals distant heights",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R16_UNORM, SIZE, SIZE, 1, 1);
			ByteBuffer empty = MemoryUtil.memCalloc(SIZE * SIZE * 2);
			try {
				device.createCommandEncoder().writeToTexture(texture, empty, 0, 0, 0, 0, SIZE, SIZE);
			} finally {
				MemoryUtil.memFree(empty);
			}
			view = device.createTextureView(texture);
		}
		ByteBuffer row = MemoryUtil.memAlloc(SIZE * 2);
		try {
			int uploaded = 0;
			for (int i = 0; i < SIZE && uploaded < ROWS_PER_FRAME; i++) {
				int r = (nextUploadRow + i) % SIZE;
				long bit = 1L << (r & 63);
				if ((dirtyRows.get(r >> 6) & bit) == 0) {
					continue;
				}
				dirtyRows.getAndUpdate(r >> 6, bits -> bits & ~bit);
				row.clear();
				for (int x = 0; x < SIZE; x++) {
					row.putShort(heights[r * SIZE + x]);
				}
				row.flip();
				device.createCommandEncoder().writeToTexture(texture, row, 0, 0, 0, r, SIZE, 1);
				uploaded++;
				nextUploadRow = (r + 1) % SIZE;
			}
		} finally {
			MemoryUtil.memFree(row);
		}
	}

	/**
	 * The top of the highest solid block (exclusive of liquids) Distant Horizons holds for the cell around
	 * world column x, z, as a world y; {@link Integer#MIN_VALUE} where it has none yet or the map is off. Read on
	 * the render thread while the worker writes: a cell read mid-update is one frame stale at worst.
	 */
	static int topAt(int x, int z) {
		if (failed || !any || worker == null || !DeferredRenderer.ownsDistantComposite()) {
			return Integer.MIN_VALUE;
		}
		int index = Math.floorMod(Math.floorDiv(z, CELL), SIZE) * SIZE + Math.floorMod(Math.floorDiv(x, CELL), SIZE);
		int value = heights[index] & 0xFFFF;
		return value == 0 ? Integer.MIN_VALUE : value + minY;
	}

	/** Chunks along the map's side (it wraps on this, so no two chunks in it share a counter). */
	private static final int CHUNKS = SIZE * CELL / 16;
	/**
	 * Per chunk of the map, wrapping like it: bumped whenever a height in the chunk changes. (Wrapping every 32
	 * chunks instead, as the water column map does, changes anywhere in the map's 8 km bumped the counters of the
	 * chunks around the camera: the water map wrote hundreds of unchanged chunks again every pass.)
	 */
	private static final java.util.concurrent.atomic.AtomicIntegerArray VERSIONS =
			new java.util.concurrent.atomic.AtomicIntegerArray(CHUNKS * CHUNKS);

	private static int versionIndex(int chunkX, int chunkZ) {
		return Math.floorMod(chunkZ, CHUNKS) * CHUNKS + Math.floorMod(chunkX, CHUNKS);
	}

	/** The version of the heights in the chunk (see VERSIONS): what {@link #topAt} gives there changes with it. */
	static int version(int chunkX, int chunkZ) {
		return VERSIONS.get(versionIndex(chunkX, chunkZ));
	}

	/**
	 * Changes whenever {@link #topAt} may change everywhere: a new level, or the map switched on or off (DH's
	 * composite taken over or given back, the worker failed or started, the first heights).
	 */
	static int epoch() {
		boolean on = !failed && any && worker != null && DeferredRenderer.ownsDistantComposite();
		return generation * 2 + (on ? 1 : 0);
	}

	/** The map, or null before there is one. */
	static GpuTextureView view() {
		return view;
	}

	/**
	 * One vec4 for DistantData: the camera's world x and z modulo the map's width in blocks, its height above
	 * the level's floor, and 1 when the map holds heights.
	 */
	static void write(Std140Builder builder) {
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		double span = (double) SIZE * CELL;
		boolean on = view != null && any && !failed && DeferredRenderer.ownsDistantComposite();
		builder.putVec4((float) (camera.x - Math.floor(camera.x / span) * span), (float) (camera.y - minY),
				(float) (camera.z - Math.floor(camera.z / span) * span), on ? 1 : 0);
	}

	/** A one-line summary for diagnostics. */
	public static String status() {
		return failed ? "FAILED (see log)" : worker == null ? "off" : any ? "on, " + SIZE + "x" + SIZE + " cells of "
				+ CELL + " blocks" : "waiting for terrain";
	}
}
