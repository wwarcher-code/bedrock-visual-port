package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.TextureSet;
import dev.bedrockvisuals.bedrock.TgaImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import org.lwjgl.system.MemoryUtil;

/**
 * Bedrock's water surface maps: the `water_still_grey` texture set's normal map and MERS, both a vertical strip
 * of 16x16 animation frames (32 in vanilla, 2 ticks each, the same layout as Java's own water_still). Each texel
 * of the water surface gets its own tilt and roughness from them, which is what makes Vibrant Visuals' water
 * reflections break up on the texel grid. Without them (no Bedrock pack), a flat, smooth texel stands in.
 */
final class WaterSurfaceMaps {
	/** Flat normal (0, 0, 1) and Bedrock's water metalness and mid roughness, when the maps are missing. */
	private static final int FLAT_NORMAL = 0xFF8080FF;
	private static final int FLAT_MERS = 0x00120030;

	private static GpuTextureView normal;
	private static GpuTextureView mers;
	private static int frames = 1;
	private static boolean loaded;

	private WaterSurfaceMaps() {
	}

	static GpuTextureView normal() {
		load();
		return normal;
	}

	static GpuTextureView mers() {
		load();
		return mers;
	}

	/** Animation frames in the strips. */
	static int frames() {
		load();
		return frames;
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		TgaImage normalImage = null;
		TgaImage mersImage = null;
		Optional<TextureSet> set = BedrockInputs.pack().flatMap(pack -> pack.textureSet("blocks/water_still_grey"));
		if (set.isPresent()) {
			normalImage = read(set.get().normal());
			mersImage = read(set.get().material().image());
		}
		if (normalImage == null || mersImage == null || normalImage.width() != 16 || mersImage.width() != 16
				|| normalImage.height() != mersImage.height()) {
			BedrockVisualsClient.LOGGER.warn("[water] no usable water_still_grey normal and MERS maps; water is flat");
			normalImage = new TgaImage(1, 1, new int[] {FLAT_NORMAL});
			mersImage = new TgaImage(1, 1, new int[] {FLAT_MERS});
		}
		frames = Math.max(1, normalImage.height() / normalImage.width());
		normal = upload("Bedrock Visuals water normal", normalImage);
		mers = upload("Bedrock Visuals water MERS", mersImage);
		BedrockVisualsClient.LOGGER.info("[water] surface maps: {} frames of {}x{}", frames, normalImage.width(),
				normalImage.width());
	}

	private static TgaImage read(Path path) {
		if (path == null) {
			return null;
		}
		try {
			return TgaImage.read(path);
		} catch (IOException e) {
			BedrockVisualsClient.LOGGER.warn("[water] could not read {}", path, e);
			return null;
		}
	}

	private static GpuTextureView upload(String label, TgaImage image) {
		GpuDevice device = RenderSystem.getDevice();
		GpuTexture texture = device.createTexture(() -> label,
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, image.width(),
				image.height(), 1, 1);
		ByteBuffer data = MemoryUtil.memAlloc(image.width() * image.height() * 4);
		try {
			for (int y = 0; y < image.height(); y++) {
				for (int x = 0; x < image.width(); x++) {
					int argb = image.argb(x, y);
					data.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) (argb >>> 24));
				}
			}
			data.flip();
			device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, image.width(), image.height());
		} finally {
			MemoryUtil.memFree(data);
		}
		return device.createTextureView(texture);
	}
}
