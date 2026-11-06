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
 * water test). From under water they're on for every surface; from above, Bedrock limits them to what's
 * under water, which the lighting pass gets from {@link WaterColumns}.</li>
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
	/** When the camera last went under water, System.nanoTime(). */
	private static long submergedSince;
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
		boolean wasSubmerged = submerged;
		submerged = level != null && camera.getFluidInCamera() == FogType.WATER;
		if (submerged && !wasSubmerged) {
			submergedSince = System.nanoTime();
		}
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

	/** How long the camera has been under water, seconds (for the water fog's transition). */
	static float secondsSubmerged() {
		return submerged ? (System.nanoTime() - submergedSince) / 1e9f : 0f;
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
	static void write(Std140Builder builder, float sunIlluminance) {
		load();
		WaterSettings.Caustics caustics = BedrockInputs.pack()
				.map(pack -> pack.water(currentBiomeWater()).caustics()).orElse(null);
		// The lighting pass applies them to everything while the camera is under water, and to what the water
		// column map says is under water otherwise.
		boolean causticsOn = view != null && caustics != null && caustics.enabled();
		float frameLength = caustics == null || caustics.frameLength() <= 0 ? 0.09f : caustics.frameLength();
		float seconds = (System.nanoTime() - START) / 1e9f;
		float frame = (float) Math.floor(seconds / frameLength) % frames;
		builder.putVec4(submerged ? 1 : 0, causticsOn ? frame : -1, caustics == null ? 0.5f : caustics.scale(),
				caustics == null ? 1 : caustics.power());
		float[] sigma = WaterPass.extinction();
		builder.putVec4(sigma[0], sigma[1], sigma[2], surfaceAbove);
		// The volumetric fog's water medium (WaterAlbedoExtinction in the captures): scattering = albedo ×
		// extinction 0.112349, phase g 0.6 (HenyeyGreensteinG.y).
		builder.putVec4(WATER_ALBEDO[0] * WATER_VOLUME_EXTINCTION, WATER_ALBEDO[1] * WATER_VOLUME_EXTINCTION,
				WATER_ALBEDO[2] * WATER_VOLUME_EXTINCTION, WATER_PHASE_G);
		// Bedrock sends CloudColor as is (white, or (0.1, 0.1, 0.15) at night); used as the clouds' albedo.
		float[] cloud = Weather.cloud(sunIlluminance);
		builder.putVec4(cameraSkyAccess(), cloud[0], cloud[1], cloud[2]);
	}

	private static float toLinear(float srgb) {
		return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4f);
	}

	private static final float WATER_PHASE_G = 0.6f;
	private static final float[] WATER_ALBEDO = {0.022877f, 0.254416f, 0.927091f};
	private static final float WATER_VOLUME_EXTINCTION = 0.112349f;

	/**
	 * How much sky the camera's surroundings see, 0 to 1, from the sky light where the camera is: Bedrock lights
	 * its fog with sky ambient through per-froxel sky visibility samples; this stands in with the camera's.
	 */
	private static float cameraSkyAccess() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return 1f;
		}
		BlockPos pos = minecraft.gameRenderer.mainCamera().blockPosition();
		float level = minecraft.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos) / 15f;
		return level * level;
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
