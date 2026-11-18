package dev.bedrockvisuals.render;

import net.minecraft.client.Minecraft;

/**
 * The clock the renderer's own animations and easings run on: the wall clock while playing, and the replay's
 * timeline during an offline export (OfflineRender). An export draws frames slower or faster than real time and
 * draws each frame several times over (its sub-frames), so wall-clock easings (exposure, the reflection probe's
 * fade, biome and settings blends, the caustics' frames) and per-frame steps (the water's ripples and waves) would
 * run at the wrong speed in the video. There the clock advances once per output frame, by the game time Flashback
 * moves the replay on (one frame of the export's frame rate).
 */
public final class RenderClock {
	private static boolean exporting;
	private static long exportNanos;
	/** Added to the wall clock while playing, so the clock runs on from where an export left it. */
	private static long offset;
	private static boolean firstSubframe = true;

	private RenderClock() {
	}

	/** At the start of each level render (each sub-frame of an offline frame), before anything reads the clock. */
	static void beginLevel(int subframe) {
		boolean now = OfflineRender.active();
		if (subframe == 0) {
			if (now && !exporting) {
				exportNanos = System.nanoTime() + offset;
			} else if (now) {
				exportNanos += (long) (gameDeltaTicks() / 20.0 * 1e9);
			} else if (exporting) {
				offset = exportNanos - System.nanoTime();
			}
		}
		exporting = now;
		firstSubframe = subframe == 0;
	}

	/** Now, in nanoseconds, on the renderer's clock (only differences mean anything, as with System.nanoTime). */
	public static long nanos() {
		return exporting ? exportNanos : System.nanoTime() + offset;
	}

	/** The game ticks this frame moves on by: the frame's own on its first sub-frame, none on the others. */
	public static float frameDeltaTicks() {
		return firstSubframe ? gameDeltaTicks() : 0f;
	}

	private static float gameDeltaTicks() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaTicks();
	}
}
