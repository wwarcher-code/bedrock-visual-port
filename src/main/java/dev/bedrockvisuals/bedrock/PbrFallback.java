package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * pbr/global.json ("minecraft:pbr_fallback_settings"): the MERS value for anything without a texture set.
 * The default when the file is absent is fully rough, non-metallic and non-emissive.
 */
public record PbrFallback(Rgba blocks, Rgba actors, Rgba particles, Rgba items) {

	static final Rgba ROUGH = new Rgba(0, 0, 1, 0);
	static final PbrFallback DEFAULT = new PbrFallback(ROUGH, ROUGH, ROUGH, ROUGH);

	static PbrFallback parse(JsonObject file) {
		JsonObject settings = BedrockJson.object(file, "minecraft:pbr_fallback_settings");
		return new PbrFallback(mers(settings, "blocks"), mers(settings, "actors"), mers(settings, "particles"),
				mers(settings, "items"));
	}

	private static Rgba mers(JsonObject settings, String group) {
		JsonObject json = BedrockJson.optionalObject(settings, group);
		if (json.has("global_metalness_emissive_roughness_subsurface")) {
			return BedrockJson.color(json.get("global_metalness_emissive_roughness_subsurface"));
		}
		if (json.has("global_metalness_emissive_roughness")) {
			// MER has no subsurface channel.
			Rgba mer = BedrockJson.color(json.get("global_metalness_emissive_roughness"));
			return new Rgba(mer.r(), mer.g(), mer.b(), 0);
		}
		return ROUGH;
	}
}
