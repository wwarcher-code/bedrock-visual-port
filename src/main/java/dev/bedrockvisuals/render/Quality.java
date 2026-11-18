package dev.bedrockvisuals.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.bedrock.BedrockInstall;
import dev.bedrockvisuals.bedrock.QualitySettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The quality dial: one of Bedrock's own graphics presets (QualitySettings, read from the install), chosen with
 * {@code /bv quality <tier> <performance|quality>} and kept in {@code config\bedrockvisuals\quality.json}. The
 * preset sets the shadow cascades (ShadowPass) and whether Bedrock's temporal anti-aliasing runs (TemporalAA;
 * F6 still switches it for the session). The default is tier 3 Performance, the preset the reference captures
 * were taken with (ultra shadows, bilinear upscaling, so no TAAU). Without the install, the ultra shadows
 * captured from it stand in.
 */
public final class Quality {
	private static final int DEFAULT_TIER = 3;
	private static final String DEFAULT_PRESET = "Performance";
	/** Bedrock's PC ultra shadows, as captured, for when the install's files can't be read. */
	private static final QualitySettings.Shadows FALLBACK_SHADOWS = new QualitySettings.Shadows(List.of(
			new QualitySettings.Cascade(0.000275f, 1e-5f, 16, 1f / 6, 2048, 1, 0),
			new QualitySettings.Cascade(0.0015f, 2e-5f, 8, 1f / 3, 2048, 1, 0),
			new QualitySettings.Cascade(0.00275f, 4e-5f, 4, 0.5f, 2048, 2, 0),
			new QualitySettings.Cascade(0.003f, 6e-5f, 4, 1, 2048, 2, 1)), 0.065f, 128, 0.4f, 16, true, true, true);

	private static QualitySettings settings;
	private static boolean loaded;
	private static QualitySettings.Preset preset;
	private static Path configFile;

	private Quality() {
	}

	/** At startup: reads Bedrock's presets and the chosen one, and applies it. */
	public static void load(Path configDir) {
		configFile = configDir.resolve("bedrockvisuals").resolve("quality.json");
		settings = BedrockInstall.dataFolder().flatMap(QualitySettings::load).orElse(null);
		loaded = true;
		int tier = DEFAULT_TIER;
		String name = DEFAULT_PRESET;
		try {
			if (Files.isRegularFile(configFile)) {
				JsonObject json = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8))
						.getAsJsonObject();
				tier = json.get("tier").getAsInt();
				name = json.get("preset").getAsString();
			}
		} catch (IOException | RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[quality] couldn't read {}; using tier {} {}", configFile, tier, name, e);
		}
		if (!select(tier, name, false)) {
			select(DEFAULT_TIER, DEFAULT_PRESET, false);
		}
		BedrockVisualsClient.LOGGER.info("[quality] {}", status());
	}

	/**
	 * Picks a preset; true when the install has it. Saves the choice when asked, and applies what the preset
	 * switches at once (temporal anti-aliasing; the shadows follow on the next frame).
	 */
	public static boolean select(int tier, String name, boolean save) {
		if (settings == null) {
			return false;
		}
		Optional<QualitySettings.Preset> found = settings.preset(tier, name);
		if (found.isEmpty()) {
			return false;
		}
		preset = found.get();
		TemporalAA.setEnabled(preset.taau());
		if (save && configFile != null) {
			try {
				Files.createDirectories(configFile.getParent());
				JsonObject json = new JsonObject();
				json.addProperty("tier", preset.tier());
				json.addProperty("preset", preset.name());
				Files.writeString(configFile, json.toString(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				BedrockVisualsClient.LOGGER.warn("[quality] couldn't save {}", configFile, e);
			}
		}
		return true;
	}

	/** The chosen preset, or empty without the install's files. */
	public static Optional<QualitySettings.Preset> preset() {
		return Optional.ofNullable(preset);
	}

	/** Every preset the install defines (for /bv quality). */
	public static List<QualitySettings.Preset> presets() {
		return settings == null ? List.of() : settings.presets();
	}

	/** The shadow cascades of the chosen preset's shadow level. */
	static QualitySettings.Shadows shadows() {
		if (settings != null && preset != null) {
			return settings.shadows(preset.shadows()).orElse(FALLBACK_SHADOWS);
		}
		return FALLBACK_SHADOWS;
	}

	public static String status() {
		if (!loaded) {
			return "not loaded";
		}
		if (preset == null) {
			return "Bedrock's presets not found (ultra shadows)";
		}
		return preset.label() + ": shadows " + preset.shadows() + " (" + shadows().cascades().size() + " cascades)"
				+ ", reflections " + preset.reflections() + ", volumetric fog " + preset.volumetricFog() + ", clouds "
				+ preset.clouds() + ", upscaling " + preset.upscalingMode()
				+ (preset.taau() != TemporalAA.isEnabled() ? " (TAA switched " + (TemporalAA.isEnabled() ? "on" : "off")
						+ " with F6)" : "");
	}
}
