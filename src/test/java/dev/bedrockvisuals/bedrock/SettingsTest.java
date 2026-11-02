package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class SettingsTest {
	private static final float EPSILON = 1e-5f;

	private static JsonObject settings(String json, String key) {
		return BedrockJson.parse(json).getAsJsonObject(key);
	}

	@Test
	void lightingReadsTheOrbitalLayout() {
		LightingSettings lighting = LightingSettings.parse("test", settings("""
				{"minecraft:lighting_settings": {
				  "directional_lights": {"orbital": {"sun": {"illuminance": 100, "color": [255, 0, 0]}, "orbital_offset_degrees": 5}},
				  "ambient": {"color": "#808080", "illuminance": 0.02}
				}}""", LightingSettings.KEY));
		assertEquals(100, lighting.sun().illuminance().sample(0), EPSILON);
		assertEquals(5, lighting.orbitalOffsetDegrees().sample(0), EPSILON);
		assertEquals(0.02f, lighting.ambientIlluminance(), EPSILON);
		assertEquals(1, lighting.skyIntensity().sample(0), EPSILON);
	}

	@Test
	void lightingReadsTheOlderFlatLayout() {
		LightingSettings lighting = LightingSettings.parse("test", settings("""
				{"minecraft:lighting_settings": {"directional_lights": {"sun": {"illuminance": 7}}}}""",
				LightingSettings.KEY));
		assertEquals(7, lighting.sun().illuminance().sample(0), EPSILON);
	}

	@Test
	void colorGradingDefaultsMatchTheDocs() {
		ColorGradingSettings grading = ColorGradingSettings.parse("test", settings("""
				{"minecraft:color_grading_settings": {"color_grading": {"midtones": {"contrast": [1.15, 1.15, 1.15]}},
				  "tone_mapping": {"operator": "reinhard_luminance"}}}""", ColorGradingSettings.KEY));
		assertEquals(Vec3.of(1.15f), grading.midtones().contrast());
		assertEquals(Vec3.of(1), grading.midtones().gain());
		assertEquals(Vec3.of(2.2f), grading.midtones().gamma());
		assertFalse(grading.shadows().enabled());
		assertTrue(grading.temperature().inverted());
		assertEquals(6500, grading.temperature().kelvin(), EPSILON);
		assertEquals(ColorGradingSettings.ToneMapping.REINHARD_LUMINANCE, grading.toneMapping());
	}

	@Test
	void fogReadsDistanceAndVolumetricMedia() {
		FogSettings fog = FogSettings.parse("test", settings("""
				{"minecraft:fog_settings": {
				  "distance": {"air": {"fog_start": 0.92, "fog_end": 1.0, "fog_color": "#ABD2FF", "render_distance_type": "render"}},
				  "volumetric": {"density": {"air": {"max_density": 0.05}},
				    "media_coefficients": {"air": {"scattering": [0.06, 0.06, 0.06]}},
				    "henyey_greenstein_g": {"air": {"henyey_greenstein_g": 0.4}}}
				}}""", FogSettings.KEY));
		assertTrue(fog.distance().get("air").relativeToRenderDistance());
		assertEquals(0.92f, fog.distance().get("air").start(), EPSILON);
		assertEquals(0.4f, fog.volumetric().get("air").henyeyGreensteinG(), EPSILON);
		assertEquals(Vec3.of(0.06f), fog.volumetric().get("air").scattering());
	}

	@Test
	void merConstantsHaveNoSubsurface() {
		JsonObject set = BedrockJson.parse("{\"color\": \"#FF0000\", \"metalness_emissive_roughness\": [255, 0, 128]}");
		TextureSet textureSet = TextureSet.parse("test", base -> java.util.Optional.empty(), set);
		assertFalse(textureSet.hasSubsurface());
		assertEquals(0, textureSet.material().constant().a(), EPSILON);
		assertEquals(1, textureSet.material().constant().r(), EPSILON);
	}

	@Test
	void javaNoonIsBedrockZero() {
		assertEquals(0, TimeOfDay.fromJavaDayTime(6000), EPSILON);
		assertEquals(0.5f, TimeOfDay.fromJavaDayTime(18000), EPSILON);
		// Eased: sunrise sits at 0.7845, not 0.75; the low-sun test time matches Bedrock's capture (0.1945).
		assertEquals(0.78452f, TimeOfDay.fromJavaDayTime(0), 1e-4);
		assertEquals(0.78452f, TimeOfDay.fromJavaDayTime(24000 * 5), 1e-4);
		assertEquals(0.19414f, TimeOfDay.fromJavaDayTime(11500), 1e-4);
	}
}
