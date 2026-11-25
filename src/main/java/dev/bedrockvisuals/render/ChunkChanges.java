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
	/** When (System.nanoTime) a block was last placed or broken in each chunk column. */
	private static final java.util.concurrent.atomic.AtomicLongArray EDITED = new java.util.concurrent.atomic.AtomicLongArray(
			WRAP * WRAP);
	/** How long after an edit a chunk's rebuilds count as that edit's (through the server, and the light after). */
	private static final long EDIT_REACH = 1_500_000_000L;
	/**
	 * Or how many frames after it: in exports a frame takes seconds to draw, so an edit's rebuild arrives long past
	 * EDIT_REACH, and the traced light around a removed light was never wiped (it kept glowing).
	 */
	private static final long EDIT_FRAMES = 120;
	/** The frame each chunk column was last edited in ({@link #frames}). */
	private static final java.util.concurrent.atomic.AtomicLongArray EDITED_FRAME = new java.util.concurrent.atomic.AtomicLongArray(
			WRAP * WRAP);
	/** Frames drawn (LevelRendererMixin). */
	private static volatile long frames = 1;
	private static volatile long editsUntilFrame;
	private static final long EDIT_WINDOW_FRAMES = 20;
	private static final AtomicInteger NEXT = new AtomicInteger();
	/** Set when anything changed since the light volume last looked (takeChanged). */
	private static volatile boolean changed;
	/** Until when (System.nanoTime) changes count as edits: a block placed or broken, and the light updates after. */
	private static volatile long editsUntil;
	private static final long EDIT_WINDOW = 300_000_000L;

	private ChunkChanges() {
	}

	/** A frame is being drawn. */
	public static void frameDrawn() {
		frames++;
	}

	private static int index(int chunkX, int chunkZ) {
		return Math.floorMod(chunkZ, WRAP) * WRAP + Math.floorMod(chunkX, WRAP);
	}

	/** Something in the chunk column changed. */
	public static void mark(int chunkX, int chunkZ) {
		STAMPS.set(index(chunkX, chunkZ), NEXT.incrementAndGet());
		changed = true;
	}

	/** A block was placed or broken in the chunk column: its changes, and the light updates after, are edits. */
	public static void markEdit(int chunkX, int chunkZ) {
		EDITED.set(index(chunkX, chunkZ), System.nanoTime());
		EDITED_FRAME.set(index(chunkX, chunkZ), frames);
		editsUntil = System.nanoTime() + EDIT_WINDOW;
		editsUntilFrame = frames + EDIT_WINDOW_FRAMES;
		mark(chunkX, chunkZ);
	}

	/**
	 * Whether anything changed since the last call, shortly after an edit (chunks loading as the camera moves
	 * aren't edits).
	 */
	static boolean takeEdited() {
		boolean was = changed && (System.nanoTime() < editsUntil || frames < editsUntilFrame);
		changed = false;
		return was;
	}

	/**
	 * Whether a block was placed or broken lately in the chunk column or one beside it (a rebuild for a block at
	 * the border reaches the neighbour). Sections are also rebuilt when the chunks around them load, their
	 * border faces changing: those aren't edits.
	 */
	public static boolean editedNear(int chunkX, int chunkZ) {
		long now = System.nanoTime();
		for (int dz = -1; dz <= 1; dz++) {
			for (int dx = -1; dx <= 1; dx++) {
				long at = EDITED.get(index(chunkX + dx, chunkZ + dz));
				if (at != 0 && (now - at < EDIT_REACH
						|| frames - EDITED_FRAME.get(index(chunkX + dx, chunkZ + dz)) < EDIT_FRAMES)) {
					return true;
				}
			}
		}
		return false;
	}

	/** The chunk column's stamp: different from an earlier one if it changed since. */
	static int stamp(int chunkX, int chunkZ) {
		return STAMPS.get(index(chunkX, chunkZ));
	}
}
