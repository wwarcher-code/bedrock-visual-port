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

	/**
	 * Uploads the strip with its mip chain, each level a 2x2 box average of the one above (normals averaged as
	 * encoded, so ripples flatten with distance as Bedrock's sampled surface maps do), down to one texel per
	 * frame: the frames stay square and stacked at every level.
	 */
	private static GpuTextureView upload(String label, TgaImage image) {
		GpuDevice device = RenderSystem.getDevice();
		int width = image.width();
		int height = image.height();
		int levels = 1 + Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(width, 1)));
		GpuTexture texture = device.createTexture(() -> label,
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1,
				levels);
		int[][] channels = new int[4][width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int argb = image.argb(x, y);
				int i = y * width + x;
				channels[0][i] = (argb >> 16) & 255;
				channels[1][i] = (argb >> 8) & 255;
				channels[2][i] = argb & 255;
				channels[3][i] = argb >>> 24;
			}
		}
		int w = width;
		int h = height;
		for (int level = 0; level < levels; level++) {
			ByteBuffer data = MemoryUtil.memAlloc(w * h * 4);
			try {
				for (int i = 0; i < w * h; i++) {
					data.put((byte) channels[0][i]).put((byte) channels[1][i]).put((byte) channels[2][i])
							.put((byte) channels[3][i]);
				}
				data.flip();
				device.createCommandEncoder().writeToTexture(texture, data, level, 0, 0, 0, w, h);
			} finally {
				MemoryUtil.memFree(data);
			}
			if (level + 1 < levels) {
				int nw = Math.max(1, w / 2);
				int nh = Math.max(1, h / 2);
				int[][] next = new int[4][nw * nh];
				for (int c = 0; c < 4; c++) {
					for (int y = 0; y < nh; y++) {
						for (int x = 0; x < nw; x++) {
							int x0 = Math.min(2 * x, w - 1);
							int x1 = Math.min(2 * x + 1, w - 1);
							int y0 = Math.min(2 * y, h - 1);
							int y1 = Math.min(2 * y + 1, h - 1);
							next[c][y * nw + x] = (channels[c][y0 * w + x0] + channels[c][y0 * w + x1]
									+ channels[c][y1 * w + x0] + channels[c][y1 * w + x1] + 2) / 4;
						}
					}
				}
				channels = next;
				w = nw;
				h = nh;
			}
		}
		return device.createTextureView(texture);
	}
}
