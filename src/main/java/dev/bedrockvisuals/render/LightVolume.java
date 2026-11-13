package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * The sky and block light around the camera, block by block, for the volumetric fog: Bedrock's PopulateVolume
 * lights each froxel's ambient by its SkyAmbientSamples texture (x: block light, times
 * BlockBaseAmbientLightColorIntensity; y: sky light, times SkyAmbientLightColorIntensity) instead of one value for
 * the whole view, and gives the sun's light only where some sky light reaches. This holds Minecraft's own light
 * levels for {@link #SIZE_XZ} x {@link #SIZE_Y} x {@link #SIZE_XZ} blocks around the camera, wrapping on world
 * coordinates (so a block always sits at the same texel), its horizontal slices side by side in a 2D texture,
 * {@link #TILES_X} across. A few slices are refreshed each frame. Blue is 1 where the block is solid and opaque
 * (isSolidRender): what the point lights' shadows are traced through.
 */
public final class LightVolume {
	static final int SIZE_XZ = 128;
	static final int SIZE_Y = 48;
	static final int TILES_X = 8;
	private static final int WIDTH = SIZE_XZ * TILES_X;
	private static final int HEIGHT = SIZE_XZ * (SIZE_Y / TILES_X);
	private static final int SLICES_PER_FRAME = 3;

	/** Whether each block is solid and opaque, by its wrapped index (for the colored block light's spread). */
	static final byte[] SOLID = new byte[SIZE_XZ * SIZE_XZ * SIZE_Y];
	/** The pack-coloured light blocks found in each slice, by slice. */
	static final java.util.concurrent.atomic.AtomicReferenceArray<java.util.List<BlockLightColors.Emitter>> EMITTERS =
			new java.util.concurrent.atomic.AtomicReferenceArray<>(SIZE_Y);

	/** A world block's index in the wrapped volume (x fastest, then z, then y). */
	static int index(int x, int y, int z) {
		return Math.floorMod(x, SIZE_XZ) + Math.floorMod(z, SIZE_XZ) * SIZE_XZ + Math.floorMod(y, SIZE_Y) * SIZE_XZ * SIZE_XZ;
	}

	private static GpuTexture texture;
	private static GpuTextureView view;
	private static int nextSlice;
	private static int filled;

	private LightVolume() {
	}

	/** Where the camera's block was when each slice was last written (the slice holds the blocks around that). */
	private static final int[] SLICE_CAMERA_X = new int[SIZE_Y];
	private static final int[] SLICE_CAMERA_Y = new int[SIZE_Y];
	private static final int[] SLICE_CAMERA_Z = new int[SIZE_Y];
	/** One slice's texels, reused. */
	private static final byte[] SLICE = new byte[SIZE_XZ * SIZE_XZ * 4];

	/**
	 * Once a frame, before the lighting pass: refreshes the next few horizontal slices around the camera. Read
	 * from the chunks' sections and the light engine's data layers directly, a chunk at a time (through
	 * Level.getBrightness and getBlockState per block, three lookups each for some 50,000 blocks a frame, the scan
	 * took several milliseconds of every frame).
	 */
	static void update() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		create();
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		int cameraX = (int) Math.floor(camera.x);
		int cameraY = (int) Math.floor(camera.y);
		int cameraZ = (int) Math.floor(camera.z);
		int minX = cameraX - SIZE_XZ / 2;
		int minZ = cameraZ - SIZE_XZ / 2;
		int maxX = minX + SIZE_XZ - 1;
		int maxZ = minZ + SIZE_XZ - 1;
		LayerLightEventListener skyLight = level.getLightEngine().getLayerListener(LightLayer.SKY);
		LayerLightEventListener blockLight = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
		int topLightSection = level.getLightEngine().getMaxLightSection();
		java.util.Map<net.minecraft.world.level.block.Block, float[]> colors = BlockLightColors.declared();
		ByteBuffer data = MemoryUtil.memAlloc(SLICE.length);
		try {
			for (int n = 0; n < SLICES_PER_FRAME; n++) {
				// The world y nearest the camera that falls on this slice.
				int slice = nextSlice;
				nextSlice = (nextSlice + 1) % SIZE_Y;
				int y = cameraY - SIZE_Y / 2 + Math.floorMod(slice - (cameraY - SIZE_Y / 2), SIZE_Y);
				int sectionY = SectionPos.blockToSectionCoord(y);
				int localY = y & 15;
				int layerBase = Math.floorMod(y, SIZE_Y) * SIZE_XZ * SIZE_XZ;
				java.util.List<BlockLightColors.Emitter> sliceEmitters = new java.util.ArrayList<>();
				for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
					for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
						LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
						LevelChunkSection section = null;
						if (chunk != null) {
							int index = chunk.getSectionIndexFromSectionY(sectionY);
							if (index >= 0 && index < chunk.getSections().length) {
								section = chunk.getSections()[index];
							}
						}
						boolean empty = section == null || section.hasOnlyAir();
						DataLayer block = blockLight.getDataLayerData(SectionPos.of(chunkX, sectionY, chunkZ));
						// The sky light's layer; where this section has none, the light engine reads the bottom of
						// the nearest one above in the column, or 15 when there is none (SkyLightSectionStorage).
						DataLayer sky = skyLight.getDataLayerData(SectionPos.of(chunkX, sectionY, chunkZ));
						int skyY = localY;
						boolean openSky = false;
						if (sky == null && level.dimensionType().hasSkyLight()) {
							skyY = 0;
							for (int above = sectionY + 1; sky == null && above <= topLightSection; above++) {
								sky = skyLight.getDataLayerData(SectionPos.of(chunkX, above, chunkZ));
							}
							openSky = sky == null;
						}
						int fromX = Math.max(chunkX << 4, minX);
						int toX = Math.min((chunkX << 4) + 15, maxX);
						int fromZ = Math.max(chunkZ << 4, minZ);
						int toZ = Math.min((chunkZ << 4) + 15, maxZ);
						for (int z = fromZ; z <= toZ; z++) {
							int row = Math.floorMod(z, SIZE_XZ);
							for (int x = fromX; x <= toX; x++) {
								int column = Math.floorMod(x, SIZE_XZ);
								int skyLevel = openSky ? 15 : sky == null ? 0 : sky.get(x & 15, skyY, z & 15);
								int blockLevel = block == null ? 0 : block.get(x & 15, localY, z & 15);
								boolean solid = false;
								if (!empty) {
									BlockState state = section.getBlockState(x & 15, localY, z & 15);
									solid = state.isSolidRender();
									if (!colors.isEmpty() && state.getLightEmission() > 0) {
										float[] color = colors.get(state.getBlock());
										if (color != null) {
											sliceEmitters.add(new BlockLightColors.Emitter(x, y, z, state.getLightEmission(), color));
										}
									}
								}
								SOLID[layerBase + row * SIZE_XZ + column] = (byte) (solid ? 1 : 0);
								int texel = (row * SIZE_XZ + column) * 4;
								SLICE[texel] = (byte) (skyLevel * 17);
								SLICE[texel + 1] = (byte) (blockLevel * 17);
								SLICE[texel + 2] = (byte) (solid ? 255 : 0);
								SLICE[texel + 3] = (byte) 255;
							}
						}
					}
				}
				data.clear();
				data.put(SLICE).flip();
				EMITTERS.set(slice, java.util.List.copyOf(sliceEmitters));
				SLICE_CAMERA_X[slice] = cameraX;
				SLICE_CAMERA_Y[slice] = cameraY;
				SLICE_CAMERA_Z[slice] = cameraZ;
				if (filled >= SIZE_Y - 1 && nextSlice == 0) {
					BlockLightColors.rebuild(cameraX, cameraY, cameraZ);
				}
				int tileX = (slice % TILES_X) * SIZE_XZ;
				int tileY = (slice / TILES_X) * SIZE_XZ;
				RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, data, 0, 0, tileX, tileY,
						SIZE_XZ, SIZE_XZ);
				filled = Math.min(SIZE_Y, filled + 1);
			}
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private static void create() {
		if (texture != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		texture = device.createTexture(() -> "Bedrock Visuals light volume",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, WIDTH, HEIGHT, 1, 1);
		ByteBuffer empty = MemoryUtil.memCalloc(WIDTH * HEIGHT * 4);
		try {
			device.createCommandEncoder().writeToTexture(texture, empty, 0, 0, 0, 0, WIDTH, HEIGHT);
		} finally {
			MemoryUtil.memFree(empty);
		}
		view = device.createTextureView(texture);
	}

	static GpuTextureView view() {
		return view;
	}

	/** The most the shader's range is pulled in by for the camera's travel since a slice was written. */
	private static final int MAX_MARGIN_XZ = 56;
	private static final int MAX_MARGIN_Y = 20;

	/**
	 * One vec4 for LightingData: the camera's world x, y, z modulo the volume's size (with their fractions), and
	 * 0 until every slice has been filled, then 1 + the margins the shader keeps from the volume's edges
	 * (horizontal + 64 × vertical, in blocks). A slice holds the blocks around where the camera was when it was
	 * written, and the camera has moved up to a full round of slices' worth since: within that much of the
	 * volume's edge a texel can still hold the block a whole volume away (flying or falling fast, wrong light
	 * and shadows flickered in at the volume's far side). After a teleport the volume is off until it has been
	 * written through again.
	 */
	static void write(Std140Builder builder) {
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		int cameraX = (int) Math.floor(camera.x);
		int cameraY = (int) Math.floor(camera.y);
		int cameraZ = (int) Math.floor(camera.z);
		int marginXZ = 0;
		int marginY = 0;
		for (int slice = 0; slice < SIZE_Y; slice++) {
			marginXZ = Math.max(marginXZ, Math.max(Math.abs(cameraX - SLICE_CAMERA_X[slice]),
					Math.abs(cameraZ - SLICE_CAMERA_Z[slice])));
			marginY = Math.max(marginY, Math.abs(cameraY - SLICE_CAMERA_Y[slice]));
		}
		boolean valid = view != null && filled >= SIZE_Y && marginXZ <= MAX_MARGIN_XZ && marginY <= MAX_MARGIN_Y;
		builder.putVec4(wrap(camera.x, SIZE_XZ), wrap(camera.y, SIZE_Y), wrap(camera.z, SIZE_XZ),
				valid ? 1 + marginXZ + 64 * marginY : 0);
	}

	private static float wrap(double value, int size) {
		double floor = Math.floor(value);
		return (float) (Math.floorMod((long) floor, size) + (value - floor));
	}

	public static String status() {
		return view == null ? "not built yet" : filled >= SIZE_Y ? "on, " + SIZE_XZ + "x" + SIZE_Y + "x" + SIZE_XZ
				+ " blocks" : "filling";
	}
}
