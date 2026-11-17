package dev.bedrockvisuals.render;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * When each chunk around the camera last changed in a way the light volume reads: a block, the light (sky or
 * block) in any of its sections, or the water column map's terrain top in it. Fed from where Minecraft marks
 * sections for a rebuild (LevelExtractorMixin, which also sees light updates: those reach the extractor directly
 * from ClientChunkCache.onLightUpdate, past ClientLevel) and from WaterColumns.
 *
 * <p>Stamps are kept per chunk column, wrapping every {@link #WRAP} chunks; chunks that far apart share a stamp,
 * which only makes a reader look at both again.
 */
public final class ChunkChanges {
	static final int WRAP = 64;
	private static final AtomicIntegerArray STAMPS = new AtomicIntegerArray(WRAP * WRAP);
	private static final AtomicInteger NEXT = new AtomicInteger();

	private ChunkChanges() {
	}

	private static int index(int chunkX, int chunkZ) {
		return Math.floorMod(chunkZ, WRAP) * WRAP + Math.floorMod(chunkX, WRAP);
	}

	/** Something in the chunk column changed. */
	public static void mark(int chunkX, int chunkZ) {
		STAMPS.set(index(chunkX, chunkZ), NEXT.incrementAndGet());
	}

	/** The chunk column's stamp: different from an earlier one if it changed since. */
	static int stamp(int chunkX, int chunkZ) {
		return STAMPS.get(index(chunkX, chunkZ));
	}
}
