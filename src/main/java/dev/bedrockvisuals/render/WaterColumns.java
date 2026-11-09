package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Where the water is around the camera, for the lighting pass: for each column, the top water body's surface
 * and the lowest water block under it.
 *
 * <p>Bedrock decides per pixel what's under water from its water prepass. PopulateVolume picks the water
 * medium between the water's front and back depths, so the light shafts in water show from above too.
 * DeferredMixedResolutionCaustics has no water test in its shader. From above, caustics show only on what's
 * under water, so the engine must limit that pass to the pixels behind the water surface. This renderer
 * lights the scene before Java draws the water, so it has no water depth then; this map stands in for it.
 *
 * <p>The map covers 512×512 columns (past any vanilla render distance this renderer is used at: with 256, the
 * last 10 to 20 blocks of water before Distant Horizons' fell outside it, a band of water shaded by the water
 * pass's fallback) and wraps on world coordinates, so a column always sits at the same
 * texel. It refreshes {@link #ROWS_PER_FRAME} rows a frame, covering the columns nearest the camera, so all
 * of it is redone every 16 frames.
 */
public final class WaterColumns {
	static final int SIZE = 512;
	/** A divisor of SIZE, so a band never runs past the last row. */
	private static final int ROWS_PER_FRAME = 32;
	/** How far down a water body is followed; deeper water counts as this deep. */
	private static final int MAX_DEPTH = 96;
	/** How far below a solid top block the map looks for water (overhangs over a lake's edge). */
	private static final int UNDER_COVER = 32;
	/**
	 * How thick a solid ledge under the surface can be for the water below it to count (a thicker run is the
	 * floor, which ends the scan). At 8, the seabed overhanging a hollow, often thicker, ended the scan: the
	 * hollow's floor fell outside the body, the lighting pass left it undimmed and the water pass shaded it as
	 * deep water (blue in the water flag view, a hard seam). Water deeper under the floor than this (a flooded
	 * cave seen from a dry one) isn't taken for the lake's.
	 */
	private static final int LEDGE_THICKNESS = 32;

	private static GpuTexture texture;
	private static GpuTextureView view;
	private static int nextRow;
	private static boolean any;
	private static int minY;
	/** Whether unloaded columns may take Distant Horizons' seabed with water to sea level (the overworld). */
	private static boolean seaFallback;
	/** The camera's x and z and the render distance in chunks, for {@link #sodiumDraws}. */
	private static double cameraX;
	private static double cameraZ;
	private static int renderDistance;
	/**
	 * Per chunk of the map (32 x 32, wrapping like the map): which chunk it was last checked for and whether
	 * Sodium drew it then. A chunk Sodium starts (or stops) drawing is mapped again at once: left to the row
	 * band, it was shaded for up to 16 frames as if Sodium weren't drawing it (as DH's water or none), a flash of
	 * different lighting as it came in.
	 */
	private static final long[] checkedChunk = new long[32 * 32];
	private static final boolean[] drawnChunk = new boolean[32 * 32];
	/**
	 * The frame until which a chunk Sodium has just started drawing is held back: marked in the map (alpha 2)
	 * so the lighting pass shows DH's picture there and the water pass leaves its water out, until whatever
	 * lights it has caught up (a frame or two came in with other lighting even with the map current).
	 */
	private static final long[] heldUntil = new long[32 * 32];
	/** Frames a new chunk is held back. */
	private static final int HOLD_FRAMES = 5;
	private static long frame;
	/** Chunks mapped again at most per frame. */
	private static final int CHUNKS_PER_FRAME = 64;

	private WaterColumns() {
	}

	/** Once a frame, before the lighting pass: refreshes the next band of rows. */
	static void update() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		create();
		minY = level.getMinY();
		seaFallback = level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD;
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		WaterColumns.cameraX = camera.x;
		WaterColumns.cameraZ = camera.z;
		renderDistance = minecraft.options.getEffectiveRenderDistance();
		int cameraX = (int) Math.floor(camera.x);
		int cameraZ = (int) Math.floor(camera.z);
		int firstRow = nextRow;
		// Never past the texture's last row (writing outside it ends the game without a Java error).
		int rows = Math.min(ROWS_PER_FRAME, SIZE - firstRow);
		nextRow = (nextRow + rows) % SIZE;
		ByteBuffer data = MemoryUtil.memAlloc(SIZE * ROWS_PER_FRAME * 8);
		try {
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int r = 0; r < rows; r++) {
				int row = firstRow + r;
				// The world z nearest the camera that falls on this row.
				int z = cameraZ - SIZE / 2 + Math.floorMod(row - (cameraZ - SIZE / 2), SIZE);
				for (int column = 0; column < SIZE; column++) {
					int x = cameraX - SIZE / 2 + Math.floorMod(column - (cameraX - SIZE / 2), SIZE);
					encode(data, level, pos, x, z);
				}
			}
			data.flip();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, firstRow, SIZE,
					rows);
		} finally {
			MemoryUtil.memFree(data);
		}
		remapChangedChunks(level, cameraX >> 4, cameraZ >> 4);
	}

	/** Whether the chunk is held back (just started being drawn; see {@link #heldUntil}). */
	private static boolean held(int chunkX, int chunkZ) {
		int index = Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32);
		return checkedChunk[index] == chunkKey(chunkX, chunkZ) && drawnChunk[index] && heldUntil[index] > frame;
	}

	private static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
	}

	/**
	 * Maps again, this frame, the chunks around the render distance whose drawn state changed, and those whose
	 * hold has just ended.
	 */
	private static void remapChangedChunks(ClientLevel level, int cameraChunkX, int cameraChunkZ) {
		frame++;
		int reach = Math.min(renderDistance + 2, 15);
		int seaY = level.getSeaLevel() - 1;
		int done = 0;
		ByteBuffer data = null;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		try {
			for (int dz = -reach; dz <= reach && done < CHUNKS_PER_FRAME; dz++) {
				for (int dx = -reach; dx <= reach && done < CHUNKS_PER_FRAME; dx++) {
					int cx = cameraChunkX + dx;
					int cz = cameraChunkZ + dz;
					int index = Math.floorMod(cz, 32) * 32 + Math.floorMod(cx, 32);
					long key = chunkKey(cx, cz);
					boolean drawn = level.getChunkSource().getChunk(cx, cz, false) != null && sodiumDraws(cx, seaY, cz);
					boolean holdEnds = checkedChunk[index] == key && drawn && heldUntil[index] != 0
							&& heldUntil[index] <= frame;
					if (checkedChunk[index] == key && drawnChunk[index] == drawn && !holdEnds) {
						continue;
					}
					boolean first = checkedChunk[index] != key;
					if (drawn && !holdEnds && (first || !drawnChunk[index])) {
						heldUntil[index] = frame + HOLD_FRAMES;
					}
					checkedChunk[index] = key;
					drawnChunk[index] = drawn;
					if (holdEnds || !drawn) {
						heldUntil[index] = 0;
					}
					if (first && !drawn) {
						continue; // newly in reach and not drawn: the row band has it as it is
					}
					if (data == null) {
						data = MemoryUtil.memAlloc(16 * 16 * 8);
					}
					data.clear();
					for (int z = cz * 16; z < cz * 16 + 16; z++) {
						for (int x = cx * 16; x < cx * 16 + 16; x++) {
							encode(data, level, pos, x, z);
						}
					}
					data.flip();
					RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, data, 0, 0,
							Math.floorMod(cx * 16, SIZE), Math.floorMod(cz * 16, SIZE), 16, 16);
					done++;
				}
			}
		} finally {
			if (data != null) {
				MemoryUtil.memFree(data);
			}
		}
	}

	/**
	 * One texel, 16 bits a channel: r the surface height × 16 above the level's floor (0 when there's no
	 * water); g the lowest water block's y above the floor (low 12 bits) and the sky light reaching the water
	 * (high 4); b the height of the underside of the first ledge under the surface with water beneath it (0
	 * for none; low 12 bits) and the sky light of the water just under it (high 4); a 1 where the column's
	 * chunk is drawn by Sodium (else any water is Distant Horizons' seabed under the sea, see below), 2 while
	 * that chunk is held back after it first appears ({@link #heldUntil}).
	 */
	private static void encode(ByteBuffer data, ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z) {
		int surface = 0;
		int bottom = 0;
		int sky = 0;
		int roof = 0;
		int skyBelow = 0;
		LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
		// Only chunks Sodium draws: Minecraft keeps a ring of chunks loaded past those it renders, where
		// Distant Horizons draws the water instead; mapped as Java's, DH's water there was taken for ground
		// under Java's water (a dark band beyond the chunk border).
		boolean rendered = chunk != null && sodiumDraws(x >> 4, level.getSeaLevel() - 1, z >> 4);
		if (rendered) {
			// MOTION_BLOCKING counts fluids, so the block under it is the top water block if there is one.
			int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, z & 15);
			pos.set(x, top, z);
			FluidState fluid = chunk.getFluidState(pos);
			// Under an overhang (a ledge, a lily pad's block, a tree over the shore) the top block is solid and
			// the water lies below it: look down a little way for it, so water in the shade counts as water (it
			// was left to the water pass's unshadowed estimate and came out bright blue).
			boolean covered = false;
			for (int below = 1; below <= UNDER_COVER && !fluid.is(FluidTags.WATER) && top - below > minY; below++) {
				pos.setY(top - below);
				FluidState under = chunk.getFluidState(pos);
				if (under.is(FluidTags.WATER)) {
					top -= below;
					fluid = under;
					covered = true;
				}
			}
			pos.setY(top);
			if (fluid.is(FluidTags.WATER)) {
				// Under cover the water fills its block up to the block above (the ledge's underside is in it).
				float height = covered ? 1f : fluid.getHeight(level, pos);
				// Down to the lowest water under it, through solid blocks in the water (a ledge under the surface
				// has water beneath it, seen from the side: it was left out and shaded as open water, bright
				// blue). Air ends the body: a cave under a lake isn't under its water.
				int y = top;
				int lowest = top - MAX_DEPTH;
				int solidRun = 0;
				for (int below = top - 1; below > lowest && below > minY && solidRun < LEDGE_THICKNESS; below--) {
					pos.setY(below);
					if (chunk.getFluidState(pos).is(FluidTags.WATER)) {
						if (solidRun > 0 && roof == 0) {
							// The first submerged ledge: the water under it sees the sky only from the side.
							roof = below + 1 - minY; // the ledge's underside
							skyBelow = level.getBrightness(LightLayer.SKY, pos);
						}
						y = below;
						solidRun = 0;
					} else if (chunk.getBlockState(pos).isAir()) {
						break;
					} else {
						solidRun++;
					}
				}
				surface = Math.max(1, Math.round((top + height - minY) * 16f));
				bottom = y - minY;
				// The sky light reaching the water: over open water the light above it; under cover the water
				// block's own, which is what spreads in from the side (the shader dims the water's ambient by it).
				pos.setY(covered ? top : top + 1);
				sky = level.getBrightness(LightLayer.SKY, pos);
				any = true;
			}
		} else if (seaFallback) {
			// Not loaded: past the loaded chunks' edge, where Distant Horizons draws the terrain. The view from
			// the near water runs on under the surface past that edge to DH's ground, which with no water mapped
			// here wasn't dimmed by the water above it (nor did the fog grid hold water there): the water pass
			// shaded it as deep water, a blue wall hanging under the chunk border. DH's seabed, where it lies
			// under the sea, has water above it up to the sea's surface (as the lighting pass assumes for DH's
			// own water).
			int ground = DistantHeights.topAt(x, z);
			int seaLevel = level.getSeaLevel();
			if (ground != Integer.MIN_VALUE && ground < seaLevel - 1) {
				surface = Math.max(1, Math.round((seaLevel - 1 + 8f / 9f - minY) * 16f));
				// Well below the ground: DH's heights are one column per 8 x 8 cell, and its ground elsewhere in
				// the cell lies lower; from the cell's height, those pixels fell outside the water and showed
				// as flickering blue specks between the near seagrass.
				bottom = Math.max(0, ground - MAX_DEPTH / 2 - minY);
				sky = 15;
				any = true;
			}
		}
		data.putShort((short) surface).putShort((short) ((bottom & 0xFFF) | (sky << 12)))
				.putShort((short) ((roof & 0xFFF) | (skyBelow << 12)))
				.putShort((short) (!rendered ? 0 : held(x >> 4, z >> 4) ? 2 : 1));
	}

	/**
	 * Whether Sodium draws the chunk's section at block height y: built, and within its render distance as its
	 * occlusion culler measures it (OcclusionCuller: the section's box, a block wider each way, nearer
	 * horizontally than the render distance × 16 blocks). It builds the square around the camera but draws a
	 * circle: built alone, the corners' chunks counted as Java's water though only DH's was drawn there, and
	 * DH's water in them was shaded as ground (unlit squares along the chunk border).
	 */
	private static boolean sodiumDraws(int chunkX, int y, int chunkZ) {
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer == null) {
			return true;
		}
		double dx = nearestToZero(chunkX * 16 - 1 - cameraX, chunkX * 16 + 17 - cameraX);
		double dz = nearestToZero(chunkZ * 16 - 1 - cameraZ, chunkZ * 16 + 17 - cameraZ);
		double radius = renderDistance * 16.0;
		return dx * dx + dz * dz < radius * radius && renderer.isSectionReady(chunkX, y >> 4, chunkZ);
	}

	/** The point of [low, high] nearest zero. */
	private static double nearestToZero(double low, double high) {
		return low > 0 ? low : high < 0 ? high : 0;
	}

	private static void create() {
		if (texture != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		texture = device.createTexture(() -> "Bedrock Visuals water columns",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA16_UNORM, SIZE, SIZE, 1, 1);
		ByteBuffer empty = MemoryUtil.memCalloc(SIZE * SIZE * 8);
		try {
			device.createCommandEncoder().writeToTexture(texture, empty, 0, 0, 0, 0, SIZE, SIZE);
		} finally {
			MemoryUtil.memFree(empty);
		}
		view = device.createTextureView(texture);
	}

	/** The map, or null before the first frame in a level. */
	static GpuTextureView view() {
		return view;
	}

	/**
	 * One vec4 for LightingData: the camera's world x and z modulo the map's size (with their fractions), the
	 * camera's height above the level's floor, and 1 when the map is there.
	 */
	static void write(Std140Builder builder) {
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		builder.putVec4(wrap(camera.x), (float) (camera.y - minY), wrap(camera.z), view != null ? 1 : 0);
	}

	private static float wrap(double value) {
		double floor = Math.floor(value);
		return (float) (Math.floorMod((long) floor, SIZE) + (value - floor));
	}

	public static String status() {
		return view == null ? "no map yet" : any ? "water mapped" : "no water nearby";
	}
}
