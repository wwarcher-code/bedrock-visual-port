package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * color_grading/*.json ("minecraft:color_grading_settings"). Midtones always apply; the shadow and
 * highlight bands apply only when enabled, below {@code shadowsMax} and above {@code highlightsMin}.
 */
public record ColorGradingSettings(
		String identifier,
		Grade midtones,
		Grade shadows,
		Grade highlights,
		Temperature temperature,
		ToneMapping toneMapping) {

	/** Per-channel grading parameters; each defaults to the identity. */
	public record Grade(boolean enabled, float threshold, Vec3 contrast, Vec3 gain, Vec3 gamma, Vec3 offset,
			Vec3 saturation) {
		static Grade parse(JsonObject json, boolean enabledByDefault, String thresholdKey, float defaultThreshold) {
			return new Grade(
					BedrockJson.bool(json, "enabled", enabledByDefault && json.size() > 0),
					thresholdKey == null ? 0 : BedrockJson.number(json, thresholdKey, defaultThreshold),
					BedrockJson.vec3(json, "contrast", Vec3.of(1)),
					BedrockJson.vec3(json, "gain", Vec3.of(1)),
					// Applied as the final encoding after tone mapping; Bedrock's documented standard is 2.2.
					BedrockJson.vec3(json, "gamma", Vec3.of(2.2f)),
					BedrockJson.vec3(json, "offset", Vec3.of(0)),
					BedrockJson.vec3(json, "saturation", Vec3.of(1)));
		}
	}

	/**
	 * @param kelvin   1000–15000; 6500 is neutral daylight
	 * @param inverted true for "white_balance", the documented default: higher values make the image cooler
	 */
	public record Temperature(boolean enabled, float kelvin, boolean inverted) {
	}

	public enum ToneMapping {
		REINHARD, REINHARD_LUMA, REINHARD_LUMINANCE, HABLE, ACES, GENERIC;

		static ToneMapping parse(String name) {
			return valueOf(name.toUpperCase(Locale.ROOT));
		}
	}

	static final String KEY = "minecraft:color_grading_settings";

	static ColorGradingSettings parse(String identifier, JsonObject settings) {
		JsonObject grading = BedrockJson.optionalObject(settings, "color_grading");
		JsonObject temperature = BedrockJson.optionalObject(grading, "temperature");
		return new ColorGradingSettings(
				identifier,
				Grade.parse(BedrockJson.optionalObject(grading, "midtones"), true, null, 0),
				Grade.parse(BedrockJson.optionalObject(grading, "shadows"), false, "shadowsMax", 0),
				Grade.parse(BedrockJson.optionalObject(grading, "highlights"), false, "highlightsMin", 1),
				new Temperature(
						BedrockJson.bool(temperature, "enabled", false),
						BedrockJson.number(temperature, "temperature", 6500),
						!"color_temperature".equals(BedrockJson.string(temperature, "type", "white_balance"))),
				ToneMapping.parse(BedrockJson.string(BedrockJson.optionalObject(settings, "tone_mapping"),
						"operator", "generic")));
	}
}
