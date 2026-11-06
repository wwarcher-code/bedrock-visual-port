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

	private static void work(DistantTerrainSource source) {
		try {
			while (running) {
				int cx = centerX;
				int cz = centerZ;
				boolean moved = false;
				// Rings outwards from the camera's cell, so the nearest far terrain arrives first.
				for (int ring = 0; ring < SIZE / 2 && running && !moved; ring++) {
					for (int dz = -ring; dz <= ring && !moved; dz++) {
						int step = (dz == -ring || dz == ring) ? 1 : 2 * ring;
						for (int dx = -ring; dx <= ring; dx += Math.max(step, 1)) {
							sample(source, cx + dx, cz + dz);
						}
						moved = Math.abs(centerX - cx) > RECENTER || Math.abs(centerZ - cz) > RECENTER;
					}
				}
				if (!moved) {
					// The whole map is done: refresh it now and then for terrain DH loads later.
					Thread.sleep(5000);
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException | LinkageError e) {
			failed = true;
			BedrockVisualsClient.LOGGER.warn("[distant] couldn't read Distant Horizons' terrain heights; far terrain "
					+ "casts no shadows", e);
		}
	}

	private static void sample(DistantTerrainSource source, int cellX, int cellZ) {
		int top = source.topSolidY(cellX * CELL + CELL / 2, cellZ * CELL + CELL / 2);
		int value = top == DistantTerrainSource.NO_HEIGHT ? 0 : Math.max(1, Math.min(65535, top - minY));
		int tx = Math.floorMod(cellX, SIZE);
		int tz = Math.floorMod(cellZ, SIZE);
		int index = tz * SIZE + tx;
		if (heights[index] != (short) value) {
			heights[index] = (short) value;
			dirtyRows.getAndUpdate(tz >> 6, bits -> bits | (1L << (tz & 63)));
			if (value != 0) {
				any = true;
			}
		}
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
