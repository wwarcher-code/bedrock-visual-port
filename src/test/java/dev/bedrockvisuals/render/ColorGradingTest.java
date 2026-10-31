package dev.bedrockvisuals.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ColorGradingTest {
	@Test
	void sixtyFiveHundredKelvinIsNeutralEitherWay() {
		for (boolean whiteBalance : new boolean[] {true, false}) {
			Vector3f neutral = ColorGrading.temperatureMultiplier(6500, whiteBalance);
			assertEquals(1, neutral.x, 1e-5f);
			assertEquals(1, neutral.y, 1e-5f);
			assertEquals(1, neutral.z, 1e-5f);
		}
	}

	@Test
	void temperatureTypesWarmInOppositeDirections() {
		// Bedrock: "white_balance" makes higher values cooler, "color_temperature" makes them warmer.
		Vector3f whiteBalanceHigh = ColorGrading.temperatureMultiplier(9000, true);
		Vector3f colorTemperatureHigh = ColorGrading.temperatureMultiplier(9000, false);
		assertTrue(whiteBalanceHigh.z > whiteBalanceHigh.x, "white balance 9000 K should be cooler");
		assertTrue(colorTemperatureHigh.x > colorTemperatureHigh.z, "color temperature 9000 K should be warmer");
	}
}
