package dev.bedrockvisuals.bedrock;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Bedrock's material (MERS) data laid out like Java's block atlas: each Java sprite's rectangle holds the
 * MERS of the Bedrock texture set mapped to it, so a shader reads a texel's material at the same texture
 * coordinates it reads its color. Texels no texture set covers get the pack's PBR fallback.
 *
 * <p>Channels are R metalness, G emissive, B roughness, A subsurface, as Bedrock stores them. A 3-channel
 * MER map has no subsurface, so its alpha is written as 0.
 */
public final class MaterialAtlasImage {
	/** Where one Java sprite sits in the atlas, in texels. */
	public record Slot(String javaTexture, int x, int y, int width, int height) {
	}

	/** ARGB pixels, row by row from the top, for each mip level; level 0 is the full size. */
	public record Result(int width, int height, List<int[]> levels, int filledSlots, List<String> problems) {
	}

	private MaterialAtlasImage() {
	}

	/**
	 * @param textureSets the Bedrock texture set for a Java texture name, if any
	 * @param fallback    the MERS used where no texture set applies
	 * @param mipLevels   how many levels to produce, matching the block atlas
	 */
	public static Result build(int width, int height, int mipLevels, List<Slot> slots,
			Function<String, Optional<TextureSet>> textureSets, Rgba fallback) {
		int[] pixels = new int[width * height];
		java.util.Arrays.fill(pixels, argb(fallback));
		Map<Path, TgaImage> decoded = new HashMap<>();
		List<String> problems = new ArrayList<>();
		int filled = 0;

		for (Slot slot : slots) {
			Optional<TextureSet> set = textureSets.apply(slot.javaTexture());
			if (set.isEmpty() || !set.get().material().isPresent()) {
				continue;
			}
			TextureSet.Layer material = set.get().material();
			boolean subsurface = set.get().hasSubsurface();
			if (material.constant() != null) {
				fill(pixels, width, slot, argb(material.constant()));
				filled++;
				continue;
			}
			TgaImage image = decoded.computeIfAbsent(material.image(), path -> decode(path, problems));
			if (image == null) {
				continue;
			}
			copyFirstFrame(pixels, width, slot, image, subsurface);
			filled++;
		}
		return new Result(width, height, mipChain(pixels, width, height, mipLevels), filled, List.copyOf(problems));
	}

	private static TgaImage decode(Path path, List<String> problems) {
		try {
			return TgaImage.read(path);
		} catch (IOException e) {
			problems.add(path + ": " + e.getMessage());
			return null;
		}
	}

	private static void fill(int[] pixels, int atlasWidth, Slot slot, int value) {
		for (int y = 0; y < slot.height(); y++) {
			int row = (slot.y() + y) * atlasWidth + slot.x();
			java.util.Arrays.fill(pixels, row, row + slot.width(), value);
		}
	}

	/**
	 * Resamples the image's first frame into the slot with nearest-texel lookups. Bedrock stores animated
	 * blocks (water, fire, sea lanterns) as a vertical strip of square frames; Java's sprite is one frame.
	 */
	private static void copyFirstFrame(int[] pixels, int atlasWidth, Slot slot, TgaImage image, boolean subsurface) {
		int frameHeight = image.height() > image.width() && image.height() % image.width() == 0
				? image.width() : image.height();
		for (int y = 0; y < slot.height(); y++) {
			int sourceY = y * frameHeight / slot.height();
			int row = (slot.y() + y) * atlasWidth + slot.x();
			for (int x = 0; x < slot.width(); x++) {
				int value = image.argb(x * image.width() / slot.width(), sourceY);
				pixels[row + x] = subsurface ? value : value & 0x00FFFFFF;
			}
		}
	}

	/** Each level is a 2x2 box filter of the one above, as the block atlas's mips are. */
	static List<int[]> mipChain(int[] level0, int width, int height, int levels) {
		List<int[]> chain = new ArrayList<>();
		chain.add(level0);
		int w = width;
		int h = height;
		for (int level = 1; level < levels && w > 1 && h > 1; level++) {
			int[] above = chain.getLast();
			int nw = w / 2;
			int nh = h / 2;
			int[] next = new int[nw * nh];
			for (int y = 0; y < nh; y++) {
				for (int x = 0; x < nw; x++) {
					next[y * nw + x] = average(above[2 * y * w + 2 * x], above[2 * y * w + 2 * x + 1],
							above[(2 * y + 1) * w + 2 * x], above[(2 * y + 1) * w + 2 * x + 1]);
				}
			}
			chain.add(next);
			w = nw;
			h = nh;
		}
		return chain;
	}

	private static int average(int a, int b, int c, int d) {
		int result = 0;
		for (int shift = 0; shift < 32; shift += 8) {
			int sum = (a >>> shift & 0xFF) + (b >>> shift & 0xFF) + (c >>> shift & 0xFF) + (d >>> shift & 0xFF);
			result |= ((sum + 2) / 4) << shift;
		}
		return result;
	}

	static int argb(Rgba color) {
		return Math.round(color.a() * 255) << 24 | Math.round(color.r() * 255) << 16
				| Math.round(color.g() * 255) << 8 | Math.round(color.b() * 255);
	}
}
