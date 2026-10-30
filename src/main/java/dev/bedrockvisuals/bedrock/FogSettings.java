package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * fogs/*.json ("minecraft:fog_settings"), keyed by medium: air, water, weather, lava, lava_resistance,
 * powder_snow for distance fog; air and water for volumetric fog. Biome fogs are partial and are read
 * merged over minecraft:fog_default.
 */
public record FogSettings(String identifier, Map<String, DistanceFog> distance, Map<String, VolumetricFog> volumetric) {

	/**
	 * @param relativeToRenderDistance "render": start and end are fractions of the render distance;
	 *                                 "fixed": they are in blocks
	 */
	public record DistanceFog(Rgba color, float start, float end, boolean relativeToRenderDistance) {
	}

	/** Henyey-Greenstein {@code g} sets forward scattering; coefficients are per meter. */
	public record VolumetricFog(float maxDensity, float zeroDensityHeight, float maxDensityHeight,
			Vec3 scattering, Vec3 absorption, float henyeyGreensteinG) {
	}

	static final String KEY = "minecraft:fog_settings";

	static FogSettings parse(String identifier, JsonObject settings) {
		Map<String, DistanceFog> distance = new HashMap<>();
		for (Map.Entry<String, JsonElement> entry : BedrockJson.optionalObject(settings, "distance").entrySet()) {
			JsonObject fog = entry.getValue().getAsJsonObject();
			distance.put(entry.getKey(), new DistanceFog(
					fog.has("fog_color") ? BedrockJson.color(fog.get("fog_color")) : Rgba.WHITE,
					BedrockJson.number(fog, "fog_start", 0),
					BedrockJson.number(fog, "fog_end", 0),
					"render".equals(BedrockJson.string(fog, "render_distance_type", "fixed"))));
		}

		JsonObject volumetricJson = BedrockJson.optionalObject(settings, "volumetric");
		JsonObject density = BedrockJson.optionalObject(volumetricJson, "density");
		JsonObject media = BedrockJson.optionalObject(volumetricJson, "media_coefficients");
		JsonObject phase = BedrockJson.optionalObject(volumetricJson, "henyey_greenstein_g");
		Map<String, VolumetricFog> volumetric = new HashMap<>();
		for (String medium : density.keySet()) {
			JsonObject d = BedrockJson.optionalObject(density, medium);
			JsonObject m = BedrockJson.optionalObject(media, medium);
			volumetric.put(medium, new VolumetricFog(
					BedrockJson.number(d, "max_density", 0),
					BedrockJson.number(d, "zero_density_height", 0),
					BedrockJson.number(d, "max_density_height", 0),
					BedrockJson.vec3(m, "scattering", Vec3.of(0)),
					BedrockJson.vec3(m, "absorption", Vec3.of(0)),
					BedrockJson.number(BedrockJson.optionalObject(phase, medium), "henyey_greenstein_g", 0)));
		}
		return new FogSettings(identifier, Map.copyOf(distance), Map.copyOf(volumetric));
	}
}
