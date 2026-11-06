package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

/**
 * Bedrock's sun and moon, drawn by the lighting pass in the sky as Bedrock's SunMoon shader draws them: the
 * pack's own texture (textures/environment/sun, moon_phases), linearised, times the sun's or moon's colour and
 * illuminance (SunMoonColor), times one plus SunMoonEmissiveMultiplier (25 for the sun, 10 for the moon in GPU
 * captures), dimmed by the volumetric fog in front of it. In HDR, before exposure, so they bloom as Bedrock's do.
 * Java's own sun and moon, drawn over the finished picture at its own brightness, are skipped while these are
 * drawn (SkyRendererMixin). Their sizes are Java's: Bedrock's quad size isn't in the shaders.
 */
public final class SunMoon {
	/** SunMoonEmissiveMultiplier in GPU captures. */
	static final float SUN_EMISSIVE = 25f;
	static final float MOON_EMISSIVE = 10f;

	private static GpuTextureView sun;
	private static GpuTextureView moon;
	private static boolean loaded;
	/** From Java's sky state each frame (SkyRendererMixin): the moon's phase and how far rain hides the sky. */
	private static volatile int moonPhase;
	private static volatile float rainBrightness = 1f;

	private SunMoon() {
	}

	public static void recordSkyState(int phase, float rain) {
		moonPhase = phase;
		rainBrightness = rain;
	}

	/** Whether the lighting pass draws the sun and moon (so Java's are skipped). */
	public static boolean drawsThem() {
		return sun != null && moon != null && DeferredRenderer.replacesSky();
	}

	/** Loads the textures once; call before a render pass is open. */
	static void prepare() {
		if (loaded) {
			return;
		}
		loaded = true;
		// Vibrant Visuals has its own sun texture (sun_vv) beside the classic one.
		sun = load("sun", "textures/environment/sun_vv", "textures/environment/sun");
		moon = load("moon phases", "textures/environment/moon_phases");
	}

	private static GpuTextureView load(String name, String... paths) {
		java.util.Map.Entry<String, byte[]> found = null;
		for (String path : paths) {
			found = BedrockInputs.pack().flatMap(pack -> pack.imageBytes(path)).orElse(null);
			if (found != null) {
				break;
			}
		}
		if (found == null) {
			BedrockVisualsClient.LOGGER.warn("[sky] no {} in the Bedrock packs; Java's sun and moon stay",
					String.join(" or ", paths));
			return null;
		}
		try {
			BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(found.getValue()));
			if (image == null) {
				throw new IOException("not an image ImageIO can read");
			}
			int width = image.getWidth();
			int height = image.getHeight();
			GpuDevice device = RenderSystem.getDevice();
			GpuTexture texture = device.createTexture(() -> "Bedrock Visuals " + name,
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			ByteBuffer data = MemoryUtil.memAlloc(width * height * 4);
			try {
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int argb = image.getRGB(x, y);
						data.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
					}
				}
				data.flip();
				device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, width, height);
			} finally {
				MemoryUtil.memFree(data);
			}
			BedrockVisualsClient.LOGGER.info("[sky] {} {}x{} from {}", name, width, height, found.getKey());
			return device.createTextureView(texture);
		} catch (IOException | RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[sky] could not load {}", found.getKey(), e);
			return null;
		}
	}

	static GpuTextureView sunView() {
		return sun;
	}

	static GpuTextureView moonView() {
		return moon;
	}

	/**
	 * One vec4 for LightingData: x how much of the sun and moon shows (0 when they aren't drawn here; Java's rain
	 * brightness, as Bedrock's SunMoonColor illuminance goes to 0 in rain), y the moon's phase (0 to 7), z and w
	 * the sun's and moon's emissive multipliers.
	 */
	static void write(Std140Builder builder) {
		builder.putVec4(drawsThem() ? rainBrightness : 0f, moonPhase, SUN_EMISSIVE, MOON_EMISSIVE);
	}

	public static String status() {
		return !loaded ? "not loaded yet" : drawsThem() ? "Bedrock's, drawn in HDR" : "Java's";
	}
}
