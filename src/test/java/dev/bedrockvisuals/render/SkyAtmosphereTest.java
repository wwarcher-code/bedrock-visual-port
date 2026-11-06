package dev.bedrockvisuals.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.bedrockvisuals.bedrock.FogSettings;
import dev.bedrockvisuals.bedrock.Rgba;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class SkyAtmosphereTest {
	@Test
	void plainsUsesTheCapturedAirMedium() {
		Vector4f[] volume = SkyAtmosphere.volume(null);
		assertEquals(0.001f, volume[0].w, 1e-6);
		assertEquals(0.75f, volume[1].z, 1e-6);
		// Full density at sea level: saturate(-y + 558.38).
		assertEquals(1f, Math.clamp(volume[1].x * 64 + volume[1].y, 0f, 1f), 1e-6);
	}

	@Test
	void renderDistanceFogStartsAtVibrantVisualsThreeQuarters() {
		FogSettings.DistanceFog air = new FogSettings.DistanceFog(Rgba.WHITE, 0.92f, 1.0f, true);
		Vector4f fog = SkyAtmosphere.distanceFog(air, null, 384);
		// The captures' FogAndDistanceControl: (0.75, 4, 384).
		assertEquals(0.75f, fog.x, 1e-6);
		assertEquals(4f, fog.y, 1e-4);
		assertEquals(384f, fog.z, 1e-6);
	}

	@Test
	void rainBlendsTowardsTheWeatherFog() {
		// Captured in rain: FogAndDistanceControl (0.23071, 2.12948), the clear (0.75, 1) blended to the
		// weather fog's (0.23, 0.7) at rain 0.9986.
		float rain = 0.9986f;
		float start = 0.75f + (0.23f - 0.75f) * rain;
		float end = 1f + (0.7f - 1f) * rain;
		assertEquals(0.23071f, start, 1e-4);
		assertEquals(2.12948f, 1f / (end - start), 1e-3);
	}

	@Test
	void waterFogGrowsInOverItsTransition() {
		FogSettings.DistanceFog water = new FogSettings.DistanceFog(Rgba.WHITE, 0f, 60f, false);
		FogSettings.TransitionFog transition = new FogSettings.TransitionFog(
				new FogSettings.DistanceFog(Rgba.WHITE, 0f, 0.01f, false), 0.25f, 5f, 0.6f, 30f);
		assertEquals(0.25f, transition.percent(0f), 1e-6);
		assertEquals(0.6f, transition.percent(5f), 1e-6);
		assertEquals(1f, transition.percent(40f), 1e-6);
		// Fully grown: 0 to 60 blocks.
		Vector4f full = SkyAtmosphere.waterFog(water, transition, 40f, 384);
		assertEquals(0f, full.x, 1e-6);
		assertEquals(1f / 60f, full.y, 1e-6);
		// The capture's 41.7 blocks (FogAndDistanceControl.y 9.21555 of 384) falls at 0.694 of the way.
		Vector4f captured = SkyAtmosphere.waterFog(water, transition, 5f + 25f * (0.694f - 0.6f) / 0.4f, 384);
		assertEquals(384f * 0.0f + 41.67f, 1f / captured.y, 0.05f);
	}
}
