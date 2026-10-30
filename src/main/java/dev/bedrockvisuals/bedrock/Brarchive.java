package dev.bedrockvisuals.bedrock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads Bedrock's .brarchive files: installed packs bundle each folder's JSON into
 * __brarchive/&lt;folder&gt;.brarchive. Uncompressed: a 16-byte header (magic, entry count, version 1),
 * then a 256-byte descriptor per entry (name length, 247-byte name, content offset, content length), then
 * the contents. All little-endian. Layout from the community spec at
 * https://github.com/theaddonn/brarchive-rs/blob/main/FORMAT.md
 */
final class Brarchive {
	private static final long MAGIC = 0x267052A0B125277DL;
	private static final int HEADER_SIZE = 16;
	private static final int DESCRIPTOR_SIZE = 256;
	private static final int NAME_SIZE = 247;

	private Brarchive() {
	}

	/** Entry name to content. Empty entries (name-only stubs) are left out. */
	static Map<String, byte[]> read(Path file) throws IOException {
		return read(Files.readAllBytes(file));
	}

	static Map<String, byte[]> read(byte[] bytes) throws IOException {
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		if (bytes.length < HEADER_SIZE || buffer.getLong(0) != MAGIC) {
			throw new IOException("not a brarchive");
		}
		int count = buffer.getInt(8);
		int version = buffer.getInt(12);
		if (version != 1) {
			throw new IOException("unknown brarchive version " + version);
		}
		long contentBase = HEADER_SIZE + (long) count * DESCRIPTOR_SIZE;
		if (count < 0 || contentBase > bytes.length) {
			throw new IOException("brarchive entry table runs past the end of the file");
		}

		Map<String, byte[]> entries = new LinkedHashMap<>();
		for (int i = 0; i < count; i++) {
			int descriptor = HEADER_SIZE + i * DESCRIPTOR_SIZE;
			int nameLength = Math.min(Byte.toUnsignedInt(bytes[descriptor]), NAME_SIZE);
			String name = new String(bytes, descriptor + 1, nameLength, StandardCharsets.UTF_8);
			long offset = Integer.toUnsignedLong(buffer.getInt(descriptor + 1 + NAME_SIZE));
			long length = Integer.toUnsignedLong(buffer.getInt(descriptor + 1 + NAME_SIZE + 4));
			if (length == 0) {
				continue;
			}
			long start = contentBase + offset;
			if (start + length > bytes.length) {
				throw new IOException("entry " + name + " runs past the end of the file");
			}
			byte[] content = new byte[(int) length];
			System.arraycopy(bytes, (int) start, content, 0, (int) length);
			entries.put(name, content);
		}
		return entries;
	}
}
