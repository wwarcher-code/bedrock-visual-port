package dev.bedrockvisuals.bedrock;

/** Converts Java's day time to the time-of-day keys Bedrock's keyframes use. */
public final class TimeOfDay {
	private static final int DAY_TICKS = 24000;
	private static final int JAVA_NOON = 6000;

	private TimeOfDay() {
	}

	/**
	 * Java's day starts at sunrise (tick 0 is 06:00, tick 6000 is noon); Bedrock's keys start at noon
	 * (0 is noon, 0.5 is midnight, 1 is the next noon). Both editions ease the sun's path, lingering near noon
	 * and midnight: {@code (2d + (0.5 - cos(πd) / 2)) / 3} of the linear fraction {@code d}. Verified against
	 * Bedrock in a GPU capture: at /time set 11500 its SunDir is (-0.939, 0.344), elevation 20°, and the sun's
	 * illuminance curve reads 38.5 there, both matching the eased 0.1945 rather than the linear 0.229.
	 */
	public static float fromJavaDayTime(long dayTime) {
		double d = Math.floorMod(dayTime - JAVA_NOON, DAY_TICKS) / (double) DAY_TICKS;
		return (float) ((2 * d + (0.5 - Math.cos(Math.PI * d) / 2)) / 3);
	}
}
