package dev.bedrockvisuals.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Eases a block of per-biome settings (all floats, as written for a uniform buffer) towards each frame's
 * values instead of switching at once. Bedrock blends biome settings rather than snapping when the player
 * crosses a biome border; without this the colour grading (warmish in savanna, default over the ocean, 4800 K
 * against 6500 K) and the sky changed abruptly at the border. TUNE: Bedrock's own blend rate for these wasn't
 * traced; this eases exponentially with a 1.8-second time constant (about 95% there after five and a half
 * seconds). A jump in the time of day (/time set) or a teleport snaps every blend at once (noteFrame), so those
 * don't drift over seconds.
 */
final class SettingsBlend {
	private static final double TIME_CONSTANT_SECONDS = 1.8;
	/** Ticks of day time between frames above which the time was set rather than passed. */
	private static final long TIME_JUMP_TICKS = 20;
	/** Blocks the camera moves between frames above which it was teleported. */
	private static final double TELEPORT_BLOCKS = 24;

	private static long snapUntilNanos;
	private static long lastDayTime = Long.MIN_VALUE;
	private static double lastX;
	private static double lastY;
	private static double lastZ;

	/**
	 * Once a frame: when the day time jumped or the camera was teleported since the last frame, every blend
	 * takes its new values at once for this frame.
	 */
	static void noteFrame(long dayTime, double x, double y, double z) {
		boolean jumped = lastDayTime != Long.MIN_VALUE && (Math.abs(dayTime - lastDayTime) > TIME_JUMP_TICKS
				|| (x - lastX) * (x - lastX) + (y - lastY) * (y - lastY) + (z - lastZ) * (z - lastZ)
						> TELEPORT_BLOCKS * TELEPORT_BLOCKS);
		lastDayTime = dayTime;
		lastX = x;
		lastY = y;
		lastZ = z;
		if (jumped) {
			snapUntilNanos = System.nanoTime() + 100_000_000L;
		}
	}

	private final int excludedFrom;
	private float[] current;
	private long lastNanos;

	/** @param excludedFrom the first float index that always takes the new value (camera-dependent terms) */
	SettingsBlend(int excludedFrom) {
		this.excludedFrom = excludedFrom;
	}

	/**
	 * Blends {@code data} (a uniform block of floats, read from its position to its limit) in place towards the
	 * new values and returns it. {@code snap} takes the new values at once.
	 */
	ByteBuffer blend(ByteBuffer data, boolean snap) {
		ByteBuffer floats = data.duplicate().order(ByteOrder.nativeOrder());
		int start = floats.position();
		int count = (floats.limit() - start) / 4;
		long now = System.nanoTime();
		snap |= now < snapUntilNanos;
		if (current == null || current.length != count) {
			current = new float[count];
			snap = true;
		}
		double seconds = lastNanos == 0 ? 0 : Math.min((now - lastNanos) / 1e9, 1.0);
		lastNanos = now;
		float keep = snap ? 0f : (float) Math.exp(-seconds / TIME_CONSTANT_SECONDS);
		for (int i = 0; i < count; i++) {
			int at = start + i * 4;
			float target = floats.getFloat(at);
			float value = i >= excludedFrom || !Float.isFinite(current[i]) ? target : target + (current[i] - target) * keep;
			current[i] = value;
			floats.putFloat(at, value);
		}
		return data;
	}
}
