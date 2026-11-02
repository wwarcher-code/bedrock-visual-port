package dev.bedrockvisuals.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class AutoExposureTest {
	private static float average(float[] luminance) {
		float[] weight = new float[luminance.length];
		Arrays.fill(weight, 1f);
		return AutoExposure.histogramAverage(luminance, weight, luminance.length);
	}

	@Test
	void aUniformFrameAveragesToItsLuminance() {
		float[] frame = new float[1000];
		Arrays.fill(frame, 1f);
		// To within a histogram bin (20 stops over 254 bins).
		assertEquals(0, Math.log(average(frame)) / Math.log(2), 20.0 / 254);
	}

	@Test
	void onlyTheBrightestFifthCounts() {
		// 80% dark ground at 0.01, 20% bright sky at 4: Bedrock meters the top 20%, the sky.
		float[] frame = new float[1000];
		Arrays.fill(frame, 0, 800, 0.01f);
		Arrays.fill(frame, 800, 1000, 4f);
		assertEquals(2, Math.log(average(frame)) / Math.log(2), 20.0 / 254 * 2);
	}

	@Test
	void tooDarkPixelsAreIgnored() {
		float[] frame = new float[1000];
		Arrays.fill(frame, 0, 900, 0.0001f);
		Arrays.fill(frame, 900, 1000, 0.5f);
		assertEquals(-1, Math.log(average(frame)) / Math.log(2), 20.0 / 254 * 2);
	}

	@Test
	void adaptationEasesTowardsTheTarget() {
		// After one second at rate 1, 1 - e^-1 of the way.
		assertEquals(1 - Math.exp(-1), AutoExposure.adapt(0, 1, 1), 1e-6);
		assertEquals(-(1 - Math.exp(-1)), AutoExposure.adapt(0, -1, 1), 1e-6);
		assertEquals(0, AutoExposure.adapt(0, 5, 0), 1e-9);
	}

	@Test
	void exposureKeysTheAverageToMiddleGrey() {
		assertEquals(AutoExposure.UNIT_SCALE * AutoExposure.KEY, AutoExposure.exposureFor(0.18f), 1e-3);
		assertEquals(AutoExposure.UNIT_SCALE * AutoExposure.KEY / 2, AutoExposure.exposureFor(0.36f), 1e-3);
		assertTrue(AutoExposure.MIN_AVERAGE < 0.02f && AutoExposure.MAX_AVERAGE == 2f);
	}
}
