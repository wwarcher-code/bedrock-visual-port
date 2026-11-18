package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class QualitySettingsTest {
	private static final float EPSILON = 1e-6f;

	private static QualitySettings sample() {
		return QualitySettings.parse(BedrockJson.parse("""
				// comments are allowed, as in Bedrock's files
				{"pc": {"tiers": {
				  "2": [{"name": "Performance", "lods": {"shadows": "low", "upscaling_mode": "bilinear"}},
				        {"name": "Quality", "lods": {"shadows": "ultra", "reflections": "high", "volumetric_fog": "medium",
				                                     "upscaling_mode": "taau", "default_deferred_distance": 20}}],
				  "1": [{"name": "Performance", "lods": {"shadows": "low"}}]
				}}}"""), BedrockJson.parse("""
				{"low": {"cascades": [[{"bias": 0.5, "pcf_width": 2, "range": 1, "resolution": 1024}]],
				         "max_shadow_distance": 64},
				 "ultra": {"cascades": [[{"bias": 0.1, "slope_bias": 0.01, "pcf_width": 16, "range": 0.25},
				                         {"bias": 0.2, "pcf_width": 4, "range": 1, "update_frequency": 2, "update_offset": 1}]],
				           "normal_offset_strength": 0.5, "moon_shadows": false}}"""), BedrockJson.parse("""
				{"high": {"ibl": {"num_samples": 32, "dimension": 256}, "ssr_enabled": false,
				          "ssr": {"linear_search_step_count": 100, "ssr_scale": 0.5}}}"""), BedrockJson.parse("""
				{"medium": {"tile_width": 16, "tile_height": 12, "depth_slices": 40, "far_plane": 200}}"""), BedrockJson.parse("""
				{"low": {"cloud_lighting_parameters": {"cloud_lighting_enabled": false}, "grid_size": 1,
				         "cloud_render_distance_scale": 2.0}}"""));
	}

	@Test
	void readsPresetsInTierOrder() {
		QualitySettings quality = sample();
		assertEquals(3, quality.presets().size());
		assertEquals(1, quality.presets().get(0).tier());
		QualitySettings.Preset preset = quality.preset(2, "quality").orElseThrow();
		assertEquals("ultra", preset.shadows());
		assertEquals("high", preset.reflections());
		assertEquals("medium", preset.volumetricFog());
		assertTrue(preset.taau());
		assertEquals(20, preset.deferredDistance());
		assertFalse(quality.preset(2, "Performance").orElseThrow().taau());
		assertEquals(Optional.empty(), quality.preset(9, "Quality"));
	}

	@Test
	void readsShadowCascades() {
		QualitySettings.Shadows ultra = sample().shadows("ultra").orElseThrow();
		assertEquals(2, ultra.cascades().size());
		assertEquals(0.1f, ultra.cascades().get(0).bias(), EPSILON);
		assertEquals(0.01f, ultra.cascades().get(0).slopeBias(), EPSILON);
		assertEquals(16, ultra.cascades().get(0).pcfWidth());
		assertEquals(2, ultra.cascades().get(1).updateFrequency());
		assertEquals(1, ultra.cascades().get(1).updateOffset());
		assertEquals(2048, ultra.cascades().get(1).resolution()); // the default when a cascade gives none
		assertEquals(0.5f, ultra.normalOffsetStrength(), EPSILON);
		assertFalse(ultra.moon());
		assertEquals(64, sample().shadows("low").orElseThrow().maxDistance(), EPSILON);
	}

	@Test
	void readsReflectionsAndFog() {
		QualitySettings.Reflections high = sample().reflections("high").orElseThrow();
		assertEquals(32, high.iblSamples());
		assertEquals(256, high.iblDimension());
		assertFalse(high.ssr());
		assertEquals(100, high.linearSteps());
		assertEquals(0.5f, high.ssrScale(), EPSILON);
		QualitySettings.VolumetricFog medium = sample().volumetricFog("medium").orElseThrow();
		assertEquals(16, medium.tileWidth());
		assertEquals(12, medium.tileHeight());
		assertEquals(40, medium.depthSlices());
		assertEquals(200, medium.farPlane(), EPSILON);
		QualitySettings.Clouds low = sample().clouds("low").orElseThrow();
		assertEquals(2, low.renderDistanceScale(), EPSILON);
		assertEquals(1, low.gridSize());
		assertFalse(low.lighting());
	}

	/** Against the installed game, when there is one: every preset's levels are defined. */
	@Test
	void installedPresetsAreComplete() {
		Optional<QualitySettings> installed = BedrockInstall.dataFolder().flatMap(QualitySettings::load);
		assumeTrue(installed.isPresent(), "Bedrock isn't installed here");
		QualitySettings quality = installed.get();
		assertFalse(quality.presets().isEmpty());
		for (QualitySettings.Preset preset : quality.presets()) {
			assertTrue(quality.shadows(preset.shadows()).isPresent(), preset.label() + " shadows");
			assertTrue(quality.reflections(preset.reflections()).isPresent(), preset.label() + " reflections");
			assertTrue(quality.volumetricFog(preset.volumetricFog()).isPresent(), preset.label() + " fog");
			assertFalse(quality.shadows(preset.shadows()).get().cascades().isEmpty(), preset.label() + " cascades");
			assertTrue(quality.clouds(preset.clouds()).isPresent(), preset.label() + " clouds");
		}
	}
}
