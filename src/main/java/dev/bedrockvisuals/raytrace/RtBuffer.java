package dev.bedrockvisuals.raytrace;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * A buffer from the ray tracing's allocator (RtDevice): its handle, its VMA allocation, its device address, its
 * size, and where the CPU can write it (0 unless it is a host buffer).
 */
record RtBuffer(long buffer, long allocation, long address, long size, long mapped) {
	/** The mapped memory, in native byte order. */
	ByteBuffer bytes() {
		if (mapped == 0) {
			throw new IllegalStateException("not a host buffer");
		}
		return MemoryUtil.memByteBuffer(mapped, (int) size).order(ByteOrder.nativeOrder());
	}
}
