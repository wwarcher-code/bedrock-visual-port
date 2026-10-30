package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonObject;

/**
 * lighting/*.json ("minecraft:lighting_settings"). Illuminance is in lux. Format 1.21.80 nests the sun and
 * moon under "orbital"; older files put them directly under "directional_lights". Both are read.
 */
public record LightingSettings(
		String identifier,
		DirectionalLight sun,
		DirectionalLight moon,
		Curve<Float> orbitalOffsetDegrees,
		DirectionalLight flash,
		float emissiveDesaturation,
		Rgba ambientColor,
		float ambientIlluminance,
		Curve<Float> skyIntensity) {

	public record DirectionalLight(Curve<Float> illuminance, Curve<Rgba> color) {
		static DirectionalLight parse(JsonObject json) {
			return new DirectionalLight(
					BedrockJson.floatCurve(json, "illuminance", 0),
					BedrockJson.colorCurve(json, "color", Rgba.WHITE));
		}
	}

	static final String KEY = "minecraft:lighting_settings";

	static LightingSettings parse(String identifier, JsonObject settings) {
		JsonObject lights = BedrockJson.optionalObject(settings, "directional_lights");
		JsonObject orbital = lights.has("orbital") ? BedrockJson.object(lights, "orbital") : lights;
		JsonObject ambient = BedrockJson.optionalObject(settings, "ambient");
		return new LightingSettings(
				identifier,
				DirectionalLight.parse(BedrockJson.optionalObject(orbital, "sun")),
				DirectionalLight.parse(BedrockJson.optionalObject(orbital, "moon")),
				BedrockJson.floatCurve(orbital, "orbital_offset_degrees", 0),
				DirectionalLight.parse(BedrockJson.optionalObject(lights, "flash")),
				BedrockJson.number(BedrockJson.optionalObject(settings, "emissive"), "desaturation", 0),
				ambient.has("color") ? BedrockJson.color(ambient.get("color")) : Rgba.WHITE,
				BedrockJson.number(ambient, "illuminance", 0),
				// Documented default: 1.0.
				BedrockJson.floatCurve(BedrockJson.optionalObject(settings, "sky"), "intensity", 1));
	}
}
