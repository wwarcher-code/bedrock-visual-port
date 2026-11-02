package dev.bedrockvisuals.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class SkyAtmosphereTest {
	private static Vector3f atElevation(double degrees) {
		double r = Math.toRadians(degrees);
		return new Vector3f((float) Math.cos(r), (float) Math.sin(r), 0);
	}

	@Test
	void fadesInAroundTheHorizon() {
		assertEquals(0f, SkyAtmosphere.fade(atElevation(-30)), 1e-6);
		assertEquals(0.5f, SkyAtmosphere.fade(atElevation(0)), 1e-6);
		assertEquals(1f, SkyAtmosphere.fade(atElevation(45)), 1e-6);
		// The low-sun reference shot: the sun about 7 degrees up.
		assertEquals(0.86f, SkyAtmosphere.fade(atElevation(7.2)), 0.01);
	}

	@Test
	void ignoresTheDirectionsLength() {
		assertEquals(SkyAtmosphere.fade(atElevation(5)), SkyAtmosphere.fade(atElevation(5).mul(3)), 1e-6);
	}
}
