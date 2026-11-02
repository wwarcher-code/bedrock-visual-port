package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.BedrockInstall;
import dev.bedrockvisuals.bedrock.WaterSettings;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import org.lwjgl.system.MemoryUtil;

/**
 * What Vibrant Visuals does while the camera is under water (verified in the shaders, see
 * docs/bedrock-renderer-notes.md, "Under water"):
 * <ul>
 * <li>Caustics: direct sun and moon light on every surface is multiplied by the engine's caustics pattern
 * (renderer/caustics.png, 64 frames of 32x32), {@code (2 tex(worldXZ × scale) × saturate(N·up))^power ×
 * (power + 1)}. The engine's caustics flag is per frame, not per pixel (the entity shaders apply it with no
 * water test), so it's on exactly while the camera is in water.</li>
 * <li>Extinction: the scene is multiplied by {@code exp(-σ d)} along the view's path through water, which
 * from under water starts at the camera; this caps the path at the water's surface above the camera.</li>
 * </ul>
 */
public final class Underwater {
	/** Caustics texture frames (the sheet's height over its width). */
	private static int frames = 1;
	private static GpuTexture texture;
	private static GpuTextureView view;
	private static boolean loaded;

	private static boolean submerged;
	/** Height of the water's surface above the camera, blocks; large when the water is very deep. */
	private static float surfaceAbove;
	private static final long START = System.nanoTime();

	private Underwater() {
	}

	/** Once a frame, before the lighting pass. */
	static void update() {
		Minecraft minecraft = Minecraft.getInstance();
		Camera camera = minecraft.gameRenderer.mainCamera();
		ClientLevel level = minecraft.level;
		submerged = level != null && camera.getFluidInCamera() == FogType.WATER;
		if (!submerged) {
			return;
		}
		double cameraY = camera.position().y;
		BlockPos.MutableBlockPos pos = camera.blockPosition().mutable();
		float top = (float) cameraY + 64;
		for (int i = 0; i < 64; i++) {
			FluidState fluid = level.getFluidState(pos);
			if (!fluid.is(FluidTags.WATER)) {
				top = pos.getY();
				break;
			}
			pos.move(0, 1, 0);
			top = pos.getY() - 1 + fluid.getHeight(level, pos.below());
		}
		surfaceAbove = Math.max(top - (float) cameraY, 0f);
	}

	static boolean submerged() {
		return submerged;
	}

	/** The caustics sheet, or null without a Bedrock install. */
	static GpuTextureView caustics() {
		load();
		return view;
	}

	/**
	 * Two vec4s for LightingData: (camera under water, caustics frame, caustics scale, caustics power), then the
	 * water's extinction per block (rgb) and the surface's height above the camera.
	 */
	static void write(Std140Builder builder) {
		load();
		WaterSettings.Caustics caustics = BedrockInputs.pack()
				.map(pack -> pack.water(currentBiomeWater()).caustics()).orElse(null);
		boolean causticsOn = submerged && view != null && caustics != null && caustics.enabled();
		float frameLength = caustics == null || caustics.frameLength() <= 0 ? 0.09f : caustics.frameLength();
		float seconds = (System.nanoTime() - START) / 1e9f;
		float frame = (float) Math.floor(seconds / frameLength) % frames;
		builder.putVec4(submerged ? 1 : 0, causticsOn ? frame : -1, caustics == null ? 0.5f : caustics.scale(),
				caustics == null ? 1 : caustics.power());
		float[] sigma = WaterPass.extinction();
		builder.putVec4(sigma[0], sigma[1], sigma[2], surfaceAbove);
	}

	private static String currentBiomeWater() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return "minecraft:default_water";
		}
		String biome = minecraft.level.getBiome(minecraft.player.blockPosition()).unwrapKey()
				.map(key -> key.identifier().toString()).orElse("minecraft:plains");
		return BedrockInputs.pack().map(pack -> pack.biome(biome).water()).orElse("minecraft:default_water");
	}

	static int frames() {
		return frames;
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		Path file = BedrockInstall.dataFolder().map(data -> data.resolve("renderer/caustics.png")).orElse(null);
		if (file == null || !Files.isRegularFile(file)) {
			BedrockVisualsClient.LOGGER.warn("[water] no renderer/caustics.png in the Bedrock install; no caustics");
			return;
		}
		try {
			BufferedImage image = ImageIO.read(file.toFile());
			int width = image.getWidth();
			int height = image.getHeight();
			frames = Math.max(1, height / width);
			GpuDevice device = RenderSystem.getDevice();
			texture = device.createTexture(() -> "Bedrock Visuals caustics",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			ByteBuffer data = MemoryUtil.memAlloc(width * height * 4);
			try {
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int argb = image.getRGB(x, y);
						data.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) 255);
					}
				}
				data.flip();
				device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, width, height);
			} finally {
				MemoryUtil.memFree(data);
			}
			view = device.createTextureView(texture);
			BedrockVisualsClient.LOGGER.info("[water] caustics: {} frames of {}x{}", frames, width, width);
		} catch (IOException | RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[water] could not load {}", file, e);
		}
	}

	public static String status() {
		load();
		return (view == null ? "no caustics texture" : "caustics " + frames + " frames")
				+ (submerged ? ", camera under water, surface " + String.format(java.util.Locale.ROOT, "%.1f", surfaceAbove)
						+ " blocks up" : "");
	}
}
