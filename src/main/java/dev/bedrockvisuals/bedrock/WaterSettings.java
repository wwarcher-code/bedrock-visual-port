package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonObject;

/** water/*.json ("minecraft:water_settings"). Particle concentrations drive the water's color and clarity. */
public record WaterSettings(
		String identifier,
		float chlorophyll,
		float suspendedSediment,
		float cdom,
		Caustics caustics,
		Waves waves) {

	public record Caustics(boolean enabled, float frameLength, float scale, float power) {
	}

	public record Waves(boolean enabled, float frequency, int octaves, float depth, float speed, float shape,
			float pull, float mix, float frequencyScaling, float speedScaling) {
	}

	static final String KEY = "minecraft:water_settings";

	static WaterSettings parse(String identifier, JsonObject settings) {
		JsonObject particles = BedrockJson.optionalObject(settings, "particle_concentrations");
		JsonObject caustics = BedrockJson.optionalObject(settings, "caustics");
		JsonObject waves = BedrockJson.optionalObject(settings, "waves");
		return new WaterSettings(
				identifier,
				BedrockJson.number(particles, "chlorophyll", 0),
				BedrockJson.number(particles, "suspended_sediment", 0),
				BedrockJson.number(particles, "cdom", 0),
				new Caustics(
						BedrockJson.bool(caustics, "enabled", false),
						BedrockJson.number(caustics, "frame_length", 0),
						BedrockJson.number(caustics, "scale", 1),
						BedrockJson.number(caustics, "power", 1)),
				new Waves(
						BedrockJson.bool(waves, "enabled", false),
						BedrockJson.number(waves, "frequency", 1),
						(int) BedrockJson.number(waves, "octaves", 1),
						BedrockJson.number(waves, "depth", 1),
						BedrockJson.number(waves, "speed", 1),
						BedrockJson.number(waves, "shape", 1),
						BedrockJson.number(waves, "pull", 0),
						BedrockJson.number(waves, "mix", 0),
						BedrockJson.number(waves, "frequency_scaling", 1),
						BedrockJson.number(waves, "speed_scaling", 1)));
	}
}
