package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonObject;

/** atmospherics/*.json ("minecraft:atmosphere_settings"): sky scattering, keyframed over the day. */
public record AtmosphereSettings(
		String identifier,
		Curve<Float> horizonBlendMin,
		Curve<Float> horizonBlendStart,
		Curve<Float> horizonBlendMieStart,
		Curve<Float> horizonBlendMax,
		Curve<Float> rayleighStrength,
		Curve<Float> sunMieStrength,
		Curve<Float> moonMieStrength,
		Curve<Float> sunGlareShape,
		Curve<Rgba> skyZenithColor,
		Curve<Rgba> skyHorizonColor) {

	static final String KEY = "minecraft:atmosphere_settings";

	static AtmosphereSettings parse(String identifier, JsonObject settings) {
		JsonObject blend = BedrockJson.optionalObject(settings, "horizon_blend_stops");
		return new AtmosphereSettings(
				identifier,
				BedrockJson.floatCurve(blend, "min", 0),
				BedrockJson.floatCurve(blend, "start", 0),
				BedrockJson.floatCurve(blend, "mie_start", 0),
				BedrockJson.floatCurve(blend, "max", 0),
				BedrockJson.floatCurve(settings, "rayleigh_strength", 0),
				BedrockJson.floatCurve(settings, "sun_mie_strength", 0),
				BedrockJson.floatCurve(settings, "moon_mie_strength", 0),
				BedrockJson.floatCurve(settings, "sun_glare_shape", 0),
				BedrockJson.colorCurve(settings, "sky_zenith_color", Rgba.WHITE),
				BedrockJson.colorCurve(settings, "sky_horizon_color", Rgba.WHITE));
	}
}
