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
		normal = upload("Bedrock Visuals water normal", normalImage, true);
		mers = upload("Bedrock Visuals water MERS", mersImage, false);
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
	 * frame: the frames stay square and stacked at every level. 16 bits a channel, so the averages (and the
	 * normal map's correction, {@link #levelTilt}) keep their fractions of an 8-bit step.
	 *
	 * @param levelTilt for the normal map: shift each frame's red and green so its average equals the whole
	 *                  loop's
	 */
	private static GpuTextureView upload(String label, TgaImage image, boolean levelTilt) {
		GpuDevice device = RenderSystem.getDevice();
		int width = image.width();
		int height = image.height();
		int levels = 1 + Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(width, 1)));
		GpuTexture texture = device.createTexture(() -> label,
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA16_UNORM, width, height, 1,
				levels);
		double[][] channels = new double[4][width * height];
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
		if (levelTilt) {
			levelTilt(channels, width, height);
		}
		int w = width;
		int h = height;
		for (int level = 0; level < levels; level++) {
			ByteBuffer data = MemoryUtil.memAlloc(w * h * 8);
			try {
				for (int i = 0; i < w * h; i++) {
					for (int c = 0; c < 4; c++) {
						data.putShort((short) Math.round(Math.max(0, Math.min(255, channels[c][i])) * 65535.0 / 255.0));
					}
				}
				data.flip();
				device.createCommandEncoder().writeToTexture(texture, data, level, 0, 0, 0, w, h);
			} finally {
				MemoryUtil.memFree(data);
			}
			if (level + 1 < levels) {
				int nw = Math.max(1, w / 2);
				int nh = Math.max(1, h / 2);
				double[][] next = new double[4][nw * nh];
				for (int c = 0; c < 4; c++) {
					for (int y = 0; y < nh; y++) {
						for (int x = 0; x < nw; x++) {
							int x0 = Math.min(2 * x, w - 1);
							int x1 = Math.min(2 * x + 1, w - 1);
							int y0 = Math.min(2 * y, h - 1);
							int y1 = Math.min(2 * y + 1, h - 1);
							next[c][y * nw + x] = (channels[c][y0 * w + x0] + channels[c][y0 * w + x1]
									+ channels[c][y1 * w + x0] + channels[c][y1 * w + x1]) / 4.0;
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

	/**
	 * Bedrock's water normal frames each lean a little differently on average: across the loop the green
	 * channel's average drifts from 126.18 (frame 0) to 126.53 (frame 31) and drops back at the wrap, 0.35 of a
	 * step where neighbouring frames differ by about 0.05. That is a 0.3° tilt of the whole surface at once,
	 * which water seen at a low angle magnifies: with the frames blended, every reflection rolled (one side of the
	 * screen up, the other down) each time the 3.2-second loop wrapped. Each frame is shifted so its average red
	 * and green equal the whole loop's; the ripples' shapes are Bedrock's.
	 */
	private static void levelTilt(double[][] channels, int width, int height) {
		int frames = Math.max(1, height / width);
		int texels = width * width;
		for (int c = 0; c < 2; c++) {
			double total = 0;
			double[] frameMean = new double[frames];
			for (int f = 0; f < frames; f++) {
				double sum = 0;
				for (int i = f * texels; i < (f + 1) * texels; i++) {
					sum += channels[c][i];
				}
				frameMean[f] = sum / texels;
				total += sum;
			}
			double loopMean = total / ((double) frames * texels);
			for (int f = 0; f < frames; f++) {
				double shift = loopMean - frameMean[f];
				for (int i = f * texels; i < (f + 1) * texels; i++) {
					channels[c][i] += shift;
				}
			}
		}
	}
}
