package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class TgaImageTest {
	private static final int RED = 0xFFFF0000;
	private static final int GREEN = 0xFF00FF00;
	private static final int BLUE = 0x800000FF;

	/** 18-byte header for a width x height image. */
	private static ByteArrayOutputStream header(int type, int bits, int width, int height, boolean topDown) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(new byte[] {0, 0, (byte) type, 0, 0, 0, 0, 0, 0, 0, 0, 0,
				(byte) width, 0, (byte) height, 0, (byte) bits, (byte) (topDown ? 0x28 : 0x08)});
		return out;
	}

	private static void bgra(ByteArrayOutputStream out, int argb) {
		out.writeBytes(new byte[] {(byte) argb, (byte) (argb >> 8), (byte) (argb >> 16), (byte) (argb >>> 24)});
	}

	@Test
	void bottomUpRowsAreFlippedToTopDown() throws IOException {
		// 1x2: stored bottom row first.
		ByteArrayOutputStream out = header(2, 32, 1, 2, false);
		bgra(out, RED);   // bottom
		bgra(out, GREEN); // top
		TgaImage image = TgaImage.decode(out.toByteArray());
		assertArrayEquals(new int[] {GREEN, RED}, image.pixels());
	}

	@Test
	void runLengthPacketsRepeatAndCopy() throws IOException {
		// 4x1 top-down: a run of three reds, then one raw blue.
		ByteArrayOutputStream out = header(10, 32, 4, 1, true);
		out.write(0x80 | 2);
		bgra(out, RED);
		out.write(0);
		bgra(out, BLUE);
		TgaImage image = TgaImage.decode(out.toByteArray());
		assertArrayEquals(new int[] {RED, RED, RED, BLUE}, image.pixels());
		assertEquals(0x80, image.argb(3, 0) >>> 24);
	}

	@Test
	void twentyFourBitPixelsAreOpaque() throws IOException {
		ByteArrayOutputStream out = header(2, 24, 1, 1, true);
		out.writeBytes(new byte[] {(byte) 0xFF, 0, 0}); // blue
		assertEquals(0xFF0000FF, TgaImage.decode(out.toByteArray()).argb(0, 0));
	}

	@Test
	void truncatedDataIsAnError() {
		ByteArrayOutputStream out = header(2, 32, 2, 2, true);
		bgra(out, RED);
		assertThrows(IOException.class, () -> TgaImage.decode(out.toByteArray()));
	}
}
