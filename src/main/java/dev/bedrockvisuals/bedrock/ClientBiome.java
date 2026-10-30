package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonObject;

/**
 * biomes/*.client_biome.json: which lighting, atmosphere, grading, water and fog settings a biome uses.
 * A missing reference falls back to the vanilla default for that kind.
 */
public record ClientBiome(
		String identifier,
		String lighting,
		String atmosphere,
		String colorGrading,
		String water,
		String fog,
		Rgba waterSurfaceColor) {

	static final String KEY = "minecraft:client_biome";

	static final ClientBiome DEFAULT = new ClientBiome("default", SettingsKind.LIGHTING.defaultId,
			SettingsKind.ATMOSPHERE.defaultId, SettingsKind.COLOR_GRADING.defaultId, SettingsKind.WATER.defaultId,
			SettingsKind.FOG.defaultId, BedrockJson.color(new com.google.gson.JsonPrimitive("#44AFF5")));

	static ClientBiome parse(String identifier, JsonObject biome) {
		JsonObject components = BedrockJson.optionalObject(biome, "components");
		JsonObject waterAppearance = BedrockJson.optionalObject(components, "minecraft:water_appearance");
		return new ClientBiome(
				identifier,
				reference(components, "minecraft:lighting_identifier", "lighting_identifier", DEFAULT.lighting),
				reference(components, "minecraft:atmosphere_identifier", "atmosphere_identifier", DEFAULT.atmosphere),
				reference(components, "minecraft:color_grading_identifier", "color_grading_identifier", DEFAULT.colorGrading),
				reference(components, "minecraft:water_identifier", "water_identifier", DEFAULT.water),
				reference(components, "minecraft:fog_appearance", "fog_identifier", DEFAULT.fog),
				waterAppearance.has("surface_color")
						? BedrockJson.color(waterAppearance.get("surface_color"))
						: DEFAULT.waterSurfaceColor);
	}

	private static String reference(JsonObject components, String component, String field, String fallback) {
		return BedrockJson.string(BedrockJson.optionalObject(components, component), field, fallback);
	}
}
