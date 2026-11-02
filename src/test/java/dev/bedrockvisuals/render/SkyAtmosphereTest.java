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
		Vector4f fog = SkyAtmosphere.distanceFog(air, 384);
		// The captures' FogAndDistanceControl: (0.75, 4, 384).
		assertEquals(0.75f, fog.x, 1e-6);
		assertEquals(4f, fog.y, 1e-4);
		assertEquals(384f, fog.z, 1e-6);
	}
}
