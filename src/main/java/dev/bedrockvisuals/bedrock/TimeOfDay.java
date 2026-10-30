package dev.bedrockvisuals.bedrock;

/** Converts Java's day time to the time-of-day keys Bedrock's keyframes use. */
public final class TimeOfDay {
	private static final int DAY_TICKS = 24000;
	private static final int JAVA_NOON = 6000;

	private TimeOfDay() {
	}

	/**
	 * Java's day starts at sunrise (tick 0 is 06:00, tick 6000 is noon); Bedrock's keys start at noon
	 * (0 is noon, 0.5 is midnight, 1 is the next noon).
	 */
	public static float fromJavaDayTime(long dayTime) {
		return Math.floorMod(dayTime - JAVA_NOON, DAY_TICKS) / (float) DAY_TICKS;
	}
}
