package dev.bedrockvisuals.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bedrock's automatic exposure (its `LuminanceHistogram` and `ColorPostProcessing` shaders, with the values in
 * the install's `renderer/average_luminance.json` and `renderer/tone_mapping.json`; see
 * docs/bedrock-renderer-notes.md). Each measurement builds a histogram of the frame's log2 luminance, centre
 * weighted, averages the brightest 20% of it, adapts towards that over time, and exposes the frame so the
 * average lands on middle grey: {@code exposure = 0.18 / average × key}.
 *
 * <p>This renderer's light values are in their own units; times {@link #UNIT_SCALE} they're Bedrock's (fitted
 * with the rest of the lighting to Bedrock screenshots, LightingState). Luminances are converted before the
 * histogram, so every threshold, range and clamp is Bedrock's own, and the exposure converted back.
 */
public final class AutoExposure {
	/**
	 * Bedrock's luminance per unit of this renderer's (fitted; this renderer's noon sun level of 1 is Bedrock's
	 * illuminance 68, near its JSON's 100). By day Bedrock's scenes are far brighter than the average clamp's 2.0,
	 * so its exposure sits at the clamp and only adapts as the scene darkens (dusk, night, caves).
	 */
	static final float UNIT_SCALE = 67.6f;
	// average_luminance.json
	static final float MIN_LOG_LUMINANCE = -3f;
	static final float LOG_LUMINANCE_RANGE = 20f;
	static final float LOW_QUANTILE = 0.80f;
	static final float HIGH_QUANTILE = 1.00f;
	static final float ADAPTIVE_TIME_COEFFICIENT = 1f;
	static final float LIGHT_ADAPTATION = 1f;
	static final float DARK_ADAPTATION = 1f;
	// tone_mapping.json: min_luminance -3 and max_luminance 4 (EV100, as 0.125 × 2^EV), exposure compensation 0.
	static final float MIN_AVERAGE = 0.125f * (float) Math.pow(2, -3);
	static final float MAX_AVERAGE = 0.125f * (float) Math.pow(2, 4);
	static final float KEY = 1f;
	/** Pixels darker than this go in the histogram's ignored bin 0 (LuminanceHistogram's threshold). */
	static final float MIN_PIXEL_LUMINANCE = 0.00095f;
	/**
	 * TUNE: the `CenterWeight` uniform is set by the engine at runtime and not found yet; a pixel counts
	 * {@code exp(-CENTER_WEIGHT × r²)}, r its distance from the screen centre in UV units.
	 */
	static final float CENTER_WEIGHT = 1f;
	private static final int BINS = 256;
	/** Every Nth pixel along each axis feeds the histogram. */
	private static final int STRIDE = 4;

	/** Adapted average luminance (Bedrock units), log2; NaN until the first measurement. */
	private static float adaptedLog = Float.NaN;
	private static long lastMeasurement;
	private static boolean snapNext;
	/** The latest measurement's target, log2 (Bedrock units). */
	private static float lastTargetLog = Float.NaN;

	private AutoExposure() {
	}

	/** The exposure this frame's lighting is multiplied by, in this renderer's units. */
	public static float exposure() {
		return exposureFor(averageLuminance());
	}

	/** The adapted, clamped average luminance in Bedrock's units (0.18 before the first measurement). */
	public static float averageLuminance() {
		if (Float.isNaN(adaptedLog)) {
			return 0.18f;
		}
		return Math.clamp((float) Math.pow(2, adaptedLog), MIN_AVERAGE, MAX_AVERAGE);
	}

	/** Whether the luminance meter is working, for diagnostics. */
	public static String meterStatus() {
		return LuminanceMeter.status();
	}

	/** Bedrock's exposure for an average luminance, converted to this renderer's units. */
	static float exposureFor(float averageLuminance) {
		return 0.18f / averageLuminance * KEY * UNIT_SCALE;
	}

	/** The next measurement replaces the adapted value instead of easing towards it. */
	public static void snap() {
		snapNext = true;
	}

	/** Jumps the adapted value to the latest measurement's target now (test captures). */
	public static void settle() {
		if (!Float.isNaN(lastTargetLog)) {
			adaptedLog = lastTargetLog;
		}
	}

	/**
	 * Takes a frame of pre-exposure luminance (R16 float, row by row, this renderer's units) and adapts towards
	 * its histogram average.
	 */
	static void accept(ByteBuffer halfFloats, int width, int height) {
		ByteBuffer data = halfFloats.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int columns = (width + STRIDE - 1) / STRIDE;
		int rows = (height + STRIDE - 1) / STRIDE;
		float[] luminance = new float[columns * rows];
		float[] weight = new float[columns * rows];
		boolean[] sky = new boolean[columns * rows];
		int i = 0;
		for (int y = 0; y < height; y += STRIDE) {
			for (int x = 0; x < width; x += STRIDE) {
				int index = (y * width + x) * 2;
				if (index + 1 >= data.limit()) {
					break;
				}
				// The lighting pass writes sky pixels negative, so diagnostics can tell sky from terrain.
				float value = Float.float16ToFloat(data.getShort(index));
				sky[i] = value < 0;
				luminance[i] = Math.abs(value) * UNIT_SCALE;
				float u = (x + 0.5f) / width - 0.5f;
				float v = (y + 0.5f) / height - 0.5f;
				weight[i] = (float) Math.exp(-CENTER_WEIGHT * (u * u + v * v));
				i++;
			}
		}
		float target = histogramAverage(luminance, weight, i);
		lastLuminance = luminance;
		lastWeight = weight;
		lastSky = sky;
		lastCount = i;
		long now = System.nanoTime();
		float seconds = lastMeasurement == 0 ? 0 : (now - lastMeasurement) / 1e9f;
		lastMeasurement = now;
		float targetLog = (float) (Math.log(target) / Math.log(2));
		lastTargetLog = targetLog;
		if (Float.isNaN(adaptedLog) || snapNext) {
			adaptedLog = targetLog;
			snapNext = false;
		} else {
			adaptedLog = adapt(adaptedLog, targetLog, Math.min(seconds, 1f));
		}
	}

	/**
	 * Bedrock's histogram average: log2 luminance in 254 bins between {@link #MIN_LOG_LUMINANCE} and
	 * {@code + LOG_LUMINANCE_RANGE} (bin 0, the too-dark pixels, ignored), averaged over the bins between the
	 * low and high quantiles of the total weight. Returns a luminance in Bedrock's units.
	 */
	static float histogramAverage(float[] luminance, float[] weight, int count) {
		double[] bins = new double[BINS];
		for (int i = 0; i < count; i++) {
			int bin = 0;
			if (luminance[i] >= MIN_PIXEL_LUMINANCE) {
				double t = (Math.log(luminance[i]) / Math.log(2) - MIN_LOG_LUMINANCE) / LOG_LUMINANCE_RANGE;
				bin = (int) (Math.clamp(t, 0, 1) * 254 + 1);
			}
			bins[bin] += weight[i];
		}
		double total = 0;
		for (int b = 1; b < BINS; b++) {
			total += bins[b];
		}
		if (total <= 0) {
			return MIN_AVERAGE;
		}
		double low = LOW_QUANTILE * total;
		double high = HIGH_QUANTILE * total;
		double running = 0;
		double sum = 0;
		double counted = 0;
		for (int b = 1; b < BINS; b++) {
			double start = Math.max(running, low);
			running += bins[b];
			double inside = Math.max(Math.min(running, high) - start, 0);
			sum += inside * b;
			counted += inside;
		}
		double meanBin = sum / Math.max(counted, 1e-9);
		double log = (meanBin - 1) / 254 * LOG_LUMINANCE_RANGE + MIN_LOG_LUMINANCE;
		return (float) Math.pow(2, log);
	}

	// The last measurement's samples, kept for diagnostics (computed on request: sorting them every
	// measurement would cost frame time).
	private static float[] lastLuminance;
	private static float[] lastWeight;
	private static boolean[] lastSky;
	private static int lastCount;

	/** What the last measurement's brightest 20% was made of, for diagnostics. */
	public static String meterBreakdown() {
		float[] luminance = lastLuminance;
		if (luminance == null) {
			return "no measurement yet";
		}
		return breakdown(luminance, lastWeight, lastSky, lastCount);
	}

	/** Sky's share of the brightest 20% (by weight), and the log-mean luminance of its sky and terrain parts. */
	static String breakdown(float[] luminance, float[] weight, boolean[] sky, int count) {
		Integer[] order = new Integer[count];
		for (int i = 0; i < count; i++) {
			order[i] = i;
		}
		java.util.Arrays.sort(order, (a, b) -> Float.compare(luminance[b], luminance[a]));
		double total = 0;
		for (int i = 0; i < count; i++) {
			total += weight[i];
		}
		double budget = (1 - LOW_QUANTILE) * total;
		double skyWeight = 0;
		double skyLog = 0;
		double terrainWeight = 0;
		double terrainLog = 0;
		for (int k = 0; k < count && budget > 0; k++) {
			int i = order[k];
			double w = Math.min(weight[i], budget);
			budget -= w;
			double log = Math.log(Math.max(luminance[i], 1e-6)) / Math.log(2);
			if (sky[i]) {
				skyWeight += w;
				skyLog += w * log;
			} else {
				terrainWeight += w;
				terrainLog += w * log;
			}
		}
		double top = skyWeight + terrainWeight;
		return String.format(java.util.Locale.ROOT, "brightest 20%%: %.0f%% sky (mean %.3g), %.0f%% terrain (mean %.3g)",
				100 * skyWeight / Math.max(top, 1e-9), skyWeight > 0 ? Math.pow(2, skyLog / skyWeight) : 0,
				100 * terrainWeight / Math.max(top, 1e-9), terrainWeight > 0 ? Math.pow(2, terrainLog / terrainWeight) : 0);
	}

	/** Log-space approach towards the target: brightening at the light rate, darkening at the dark rate. */
	static float adapt(float currentLog, float targetLog, float seconds) {
		float rate = targetLog > currentLog ? LIGHT_ADAPTATION : DARK_ADAPTATION;
		float blend = 1f - (float) Math.exp(-seconds * ADAPTIVE_TIME_COEFFICIENT * rate);
		return currentLog + (targetLog - currentLog) * blend;
	}
}
