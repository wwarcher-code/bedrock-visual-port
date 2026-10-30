package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * local_lighting/local_lighting.json ("minecraft:local_light_settings"), or point_lights/global.json before
 * 1.21.120: light color per block, and whether the block is a static light or a dynamic point light.
 */
public record LocalLights(Map<String, Light> byBlock) {

	public record Light(Rgba color, boolean pointLight) {
	}

	static final LocalLights NONE = new LocalLights(Map.of());

	static LocalLights parse(JsonObject file) {
		JsonObject settings = file.has("minecraft:local_light_settings")
				? BedrockJson.object(file, "minecraft:local_light_settings")
				: BedrockJson.optionalObject(BedrockJson.optionalObject(file, "minecraft:point_light_settings"), "colors");
		Map<String, Light> lights = new HashMap<>();
		for (Map.Entry<String, JsonElement> entry : settings.entrySet()) {
			if (entry.getValue().isJsonObject()) {
				JsonObject light = entry.getValue().getAsJsonObject();
				lights.put(entry.getKey(), new Light(
						light.has("light_color") ? BedrockJson.color(light.get("light_color")) : Rgba.WHITE,
						"point_light".equals(BedrockJson.string(light, "light_type", "static_light"))));
			} else {
				// The deprecated point light file maps each block straight to a color.
				lights.put(entry.getKey(), new Light(BedrockJson.color(entry.getValue()), true));
			}
		}
		return new LocalLights(Map.copyOf(lights));
	}
}
