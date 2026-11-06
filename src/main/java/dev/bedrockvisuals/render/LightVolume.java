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
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * The sky and block light around the camera, block by block, for the volumetric fog: Bedrock's PopulateVolume
 * lights each froxel's ambient by its SkyAmbientSamples texture (x: block light, times
 * BlockBaseAmbientLightColorIntensity; y: sky light, times SkyAmbientLightColorIntensity) instead of one value for
 * the whole view, and gives the sun's light only where some sky light reaches. This holds Minecraft's own light
 * levels for {@link #SIZE_XZ} x {@link #SIZE_Y} x {@link #SIZE_XZ} blocks around the camera, wrapping on world
 * coordinates (so a block always sits at the same texel), its horizontal slices side by side in a 2D texture,
 * {@link #TILES_X} across. A few slices are refreshed each frame.
 */
public final class LightVolume {
	static final int SIZE_XZ = 128;
	static final int SIZE_Y = 48;
	static final int TILES_X = 8;
	private static final int WIDTH = SIZE_XZ * TILES_X;
	private static final int HEIGHT = SIZE_XZ * (SIZE_Y / TILES_X);
	private static final int SLICES_PER_FRAME = 3;

	private static GpuTexture texture;
	private static GpuTextureView view;
	private static int nextSlice;
	private static int filled;

	private LightVolume() {
	}

	/** Once a frame, before the lighting pass: refreshes the next few horizontal slices around the camera. */
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
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		ByteBuffer data = MemoryUtil.memAlloc(SIZE_XZ * SIZE_XZ * 4);
		try {
			for (int n = 0; n < SLICES_PER_FRAME; n++) {
				// The world y nearest the camera that falls on this slice.
				int slice = nextSlice;
				nextSlice = (nextSlice + 1) % SIZE_Y;
				int y = cameraY - SIZE_Y / 2 + Math.floorMod(slice - (cameraY - SIZE_Y / 2), SIZE_Y);
				data.clear();
				for (int row = 0; row < SIZE_XZ; row++) {
					int z = cameraZ - SIZE_XZ / 2 + Math.floorMod(row - (cameraZ - SIZE_XZ / 2), SIZE_XZ);
					for (int column = 0; column < SIZE_XZ; column++) {
						int x = cameraX - SIZE_XZ / 2 + Math.floorMod(column - (cameraX - SIZE_XZ / 2), SIZE_XZ);
						pos.set(x, y, z);
						int sky = level.getBrightness(LightLayer.SKY, pos);
						int block = level.getBrightness(LightLayer.BLOCK, pos);
						data.put((byte) (sky * 17)).put((byte) (block * 17)).put((byte) 0).put((byte) 255);
					}
				}
				data.flip();
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

	/**
	 * One vec4 for LightingData: the camera's world x, y, z modulo the volume's size (with their fractions), and
	 * 1 once every slice has been filled.
	 */
	static void write(Std140Builder builder) {
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		builder.putVec4(wrap(camera.x, SIZE_XZ), wrap(camera.y, SIZE_Y), wrap(camera.z, SIZE_XZ),
				view != null && filled >= SIZE_Y ? 1 : 0);
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
