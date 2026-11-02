package dev.bedrockvisuals.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.bedrockvisuals.bedrock.TimeOfDay;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class LightingStateTest {
	private static final float EPSILON = 1e-4f;

	private static void assertDirection(float x, float y, float z, Vector3f actual) {
		assertEquals(x, actual.x, EPSILON, actual::toString);
		assertEquals(y, actual.y, EPSILON, actual::toString);
		assertEquals(z, actual.z, EPSILON, actual::toString);
	}

	@Test
	void sunFollowsMinecraftsDay() {
		// Java ticks: 0 sunrise, 6000 noon, 12000 sunset, 18000 midnight. The sun rises in the east (+X).
		assertDirection(1, 0, 0, LightingState.orbit(TimeOfDay.fromJavaDayTime(0), 0));
		assertDirection(0, 1, 0, LightingState.orbit(TimeOfDay.fromJavaDayTime(6000), 0));
		assertDirection(-1, 0, 0, LightingState.orbit(TimeOfDay.fromJavaDayTime(12000), 0));
		assertDirection(0, -1, 0, LightingState.orbit(TimeOfDay.fromJavaDayTime(18000), 0));
	}

	@Test
	void orbitalOffsetTiltsTheNoonSun() {
		Vector3f noon = LightingState.orbit(0, 30);
		assertEquals((float) Math.cos(Math.toRadians(30)), noon.y, EPSILON);
		assertEquals(0, noon.x, EPSILON);
	}

	@Test
	void exposureBrightensAsTheLightFades() {
		assertEquals(5.865f, LightingState.exposure(1), EPSILON);
		assertEquals(5.865f / 0.99f, LightingState.exposure(0.99f), EPSILON);
		assertEquals(5.865f * 1.022f, LightingState.exposure(0.25f), EPSILON);
		assertEquals(5.865f * 1.022f, LightingState.exposure(0), EPSILON);
	}

	@Test
	void blockLightGlowsAtNightAndFadesByDay() {
		assertEquals(0.024f, LightingState.blockLightStrength(1), EPSILON);
		assertEquals(0.024f / 0.03f, LightingState.blockLightStrength(0.03f), EPSILON);
		assertEquals(0.024f / 0.021f, LightingState.blockLightStrength(0), EPSILON);
	}
}
