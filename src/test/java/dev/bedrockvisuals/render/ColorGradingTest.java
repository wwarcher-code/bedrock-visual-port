package dev.bedrockvisuals.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColorGradingTest {
	/** The matrix applied to white. */
	private static float[] white(float kelvin, boolean colorTemperature) {
		float[] m = ColorGrading.whiteBalance(kelvin, colorTemperature);
		return new float[] {m[0] + m[1] + m[2], m[3] + m[4] + m[5], m[6] + m[7] + m[8]};
	}

	@Test
	void sixtyFiveHundredKelvinIsNearlyNeutralEitherWay() {
		// D65 sits a little off the Planckian locus, so 6500 K isn't exactly identity in Bedrock either.
		for (boolean colorTemperature : new boolean[] {true, false}) {
			float[] w = white(6500, colorTemperature);
			assertEquals(1, w[0], 0.05f);
			assertEquals(1, w[1], 0.05f);
			assertEquals(1, w[2], 0.05f);
		}
	}

	@Test
	void colorTemperatureFollowsThePacksNames() {
		// The pack's "cold" grading is 10000 K and "hot" 4000 K, both color_temperature: higher is bluer.
		float[] cold = white(10000, true);
		float[] hot = white(4000, true);
		assertTrue(cold[2] > cold[0], "color_temperature 10000 K should be cooler");
		assertTrue(hot[0] > hot[2], "color_temperature 4000 K should be warmer");
	}

	@Test
	void whiteBalanceGoesTheOtherWay() {
		float[] high = white(10000, false);
		assertTrue(high[0] > high[2], "white_balance 10000 K corrects a blue light, so it warms");
	}
}
