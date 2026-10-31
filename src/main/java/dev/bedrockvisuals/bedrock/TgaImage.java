package dev.bedrockvisuals.bedrock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A decoded Truevision TGA image, the format of Bedrock's material maps. Minecraft only reads PNG, so this
 * covers what Bedrock's packs use: true-color (24 and 32 bit), grayscale and color-mapped images, each
 * uncompressed or run-length encoded, stored bottom-up or top-down.
 *
 * @param pixels ARGB, row by row from the top
 */
public record TgaImage(int width, int height, int[] pixels) {
	private static final int COLOR_MAPPED = 1;
	private static final int TRUE_COLOR = 2;
	private static final int GRAYSCALE = 3;
	private static final int RLE_FLAG = 8;

	public int argb(int x, int y) {
		return pixels[y * width + x];
	}

	public static TgaImage read(Path file) throws IOException {
		return decode(Files.readAllBytes(file));
	}

	public static TgaImage decode(byte[] bytes) throws IOException {
		if (bytes.length < 18) {
			throw new IOException("too short for a TGA header");
		}
		ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		int idLength = in.get(0) & 0xFF;
		int colorMapType = in.get(1) & 0xFF;
		int imageType = in.get(2) & 0xFF;
		int colorMapStart = in.getShort(3) & 0xFFFF;
		int colorMapLength = in.getShort(5) & 0xFFFF;
		int colorMapBits = in.get(7) & 0xFF;
		int width = in.getShort(12) & 0xFFFF;
		int height = in.getShort(14) & 0xFFFF;
		int bits = in.get(16) & 0xFF;
		int descriptor = in.get(17) & 0xFF;
		boolean topDown = (descriptor & 0x20) != 0;
		boolean rightToLeft = (descriptor & 0x10) != 0;

		int baseType = imageType & ~RLE_FLAG;
		boolean rle = (imageType & RLE_FLAG) != 0;
		if (baseType != COLOR_MAPPED && baseType != TRUE_COLOR && baseType != GRAYSCALE) {
			throw new IOException("unsupported TGA image type " + imageType);
		}
		if (width == 0 || height == 0) {
			throw new IOException("empty TGA image");
		}

		in.position(18 + idLength);
		int[] palette = null;
		if (colorMapType == 1) {
			palette = new int[colorMapStart + colorMapLength];
			for (int i = 0; i < colorMapLength; i++) {
				palette[colorMapStart + i] = readColor(in, colorMapBits, false);
			}
		} else if (baseType == COLOR_MAPPED) {
			throw new IOException("color-mapped TGA without a color map");
		}

		int count = width * height;
		int[] stored = new int[count];
		int index = 0;
		try {
			while (index < count) {
				int run = 1;
				boolean repeat = false;
				if (rle) {
					int header = in.get() & 0xFF;
					run = (header & 0x7F) + 1;
					repeat = (header & 0x80) != 0;
				}
				if (repeat) {
					int value = readPixel(in, baseType, bits, palette);
					for (int i = 0; i < run && index < count; i++) {
						stored[index++] = value;
					}
				} else {
					for (int i = 0; i < run && index < count; i++) {
						stored[index++] = readPixel(in, baseType, bits, palette);
					}
				}
			}
		} catch (java.nio.BufferUnderflowException e) {
			throw new IOException("TGA pixel data ends early");
		}

		int[] pixels = new int[count];
		for (int row = 0; row < height; row++) {
			int targetRow = topDown ? row : height - 1 - row;
			for (int column = 0; column < width; column++) {
				int targetColumn = rightToLeft ? width - 1 - column : column;
				pixels[targetRow * width + targetColumn] = stored[row * width + column];
			}
		}
		return new TgaImage(width, height, pixels);
	}

	private static int readPixel(ByteBuffer in, int baseType, int bits, int[] palette) throws IOException {
		if (baseType == COLOR_MAPPED) {
			int entry = bits == 16 ? in.getShort() & 0xFFFF : in.get() & 0xFF;
			if (entry >= palette.length) {
				throw new IOException("TGA color index out of range");
			}
			return palette[entry];
		}
		return readColor(in, bits, baseType == GRAYSCALE);
	}

	/** One stored color as ARGB. True-color channels are stored blue, green, red, alpha. */
	private static int readColor(ByteBuffer in, int bits, boolean grayscale) throws IOException {
		if (grayscale) {
			int gray = in.get() & 0xFF;
			int alpha = bits == 16 ? in.get() & 0xFF : 0xFF;
			return alpha << 24 | gray << 16 | gray << 8 | gray;
		}
		switch (bits) {
			case 32: {
				int b = in.get() & 0xFF;
				int g = in.get() & 0xFF;
				int r = in.get() & 0xFF;
				int a = in.get() & 0xFF;
				return a << 24 | r << 16 | g << 8 | b;
			}
			case 24: {
				int b = in.get() & 0xFF;
				int g = in.get() & 0xFF;
				int r = in.get() & 0xFF;
				return 0xFF << 24 | r << 16 | g << 8 | b;
			}
			case 15:
			case 16: {
				int value = in.getShort() & 0xFFFF;
				int r = (value >> 10 & 0x1F) * 255 / 31;
				int g = (value >> 5 & 0x1F) * 255 / 31;
				int b = (value & 0x1F) * 255 / 31;
				return 0xFF << 24 | r << 16 | g << 8 | b;
			}
			default:
				throw new IOException("unsupported TGA pixel depth " + bits);
		}
	}
}
