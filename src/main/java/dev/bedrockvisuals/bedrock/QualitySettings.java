package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bedrock's graphics quality presets, as its install defines them for PC (Content\data\renderer\platform_config\pc):
 * platform_configuration.pc.json lists hardware tiers 1 to 5, each with a "Performance" and a "Quality" preset; a
 * preset picks a level (low, medium, high, ultra) for shadows, reflections, volumetric fog and clouds, an upscaling
 * mode (taau or bilinear), a target resolution and the deferred render distance. The per-feature files say what
 * each level is: shadow cascades (shadow_configuration.pc.json), the sky probe's convolution and screen-space
 * reflections (reflection_configuration.pc.json) and the volumetric fog's froxel grid
 * (volumetric_fog_configuration.pc.json). Read from the install at run time, so none of Mojang's files are kept.
 */
public final class QualitySettings {
	/** One preset of a tier ("Performance" or "Quality"). */
	public record Preset(int tier, String name, String shadows, String reflections, String volumetricFog,
			String clouds, String blockLights, boolean bloom, String upscalingMode, String targetResolution,
			int deferredDistance) {
		/** Whether Bedrock runs its temporal anti-aliasing (TAAU) with this preset. */
		public boolean taau() {
			return "taau".equals(upscalingMode);
		}

		public String label() {
			return "tier " + tier + " " + name;
		}
	}

	/** One shadow cascade: Bedrock's biases, filter width, share of the shadow distance and map size. */
	public record Cascade(float bias, float slopeBias, int pcfWidth, float range, int resolution, int updateFrequency,
			int updateOffset) {
	}

	public record Shadows(List<Cascade> cascades, float normalOffsetStrength, float maxDistance,
			float cloudShadowContribution, float cloudShadowPcfWidth, boolean sun, boolean moon, boolean clouds) {
	}

	public record Reflections(int iblSamples, int iblDimension, boolean ssr, int linearSteps, int binarySteps,
			float thickness, float fadingDistance, float roughnessCutoff, float roughnessFadeStart, float ssrScale) {
	}

	public record VolumetricFog(int tileWidth, int tileHeight, int depthSlices, float nearPlane, float farPlane,
			float shadowPcfScale) {
	}

	private final List<Preset> presets;
	private final Map<String, Shadows> shadows;
	private final Map<String, Reflections> reflections;
	private final Map<String, VolumetricFog> fog;

	QualitySettings(List<Preset> presets, Map<String, Shadows> shadows, Map<String, Reflections> reflections,
			Map<String, VolumetricFog> fog) {
		this.presets = List.copyOf(presets);
		this.shadows = Map.copyOf(shadows);
		this.reflections = Map.copyOf(reflections);
		this.fog = Map.copyOf(fog);
	}

	/** Reads the PC platform configuration from the install's data folder; empty when it isn't there. */
	public static Optional<QualitySettings> load(Path dataFolder) {
		Path pc = dataFolder.resolve("renderer").resolve("platform_config").resolve("pc");
		if (!Files.isRegularFile(pc.resolve("platform_configuration.pc.json"))) {
			return Optional.empty();
		}
		try {
			return Optional.of(parse(read(pc, "platform_configuration.pc.json"), read(pc, "shadow_configuration.pc.json"),
					read(pc, "reflection_configuration.pc.json"), read(pc, "volumetric_fog_configuration.pc.json")));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	private static JsonObject read(Path folder, String name) throws IOException {
		Path file = folder.resolve(name);
		return Files.isRegularFile(file) ? BedrockJson.read(file) : new JsonObject();
	}

	static QualitySettings parse(JsonObject platform, JsonObject shadowFile, JsonObject reflectionFile,
			JsonObject fogFile) {
		List<Preset> presets = new ArrayList<>();
		JsonObject root = platform.has("pc") ? platform.getAsJsonObject("pc") : platform;
		JsonObject tiers = BedrockJson.optionalObject(root, "tiers");
		if (tiers != null) {
			for (Map.Entry<String, JsonElement> tier : tiers.entrySet()) {
				int number;
				try {
					number = Integer.parseInt(tier.getKey());
				} catch (NumberFormatException e) {
					continue;
				}
				if (!tier.getValue().isJsonArray()) {
					continue;
				}
				for (JsonElement entry : tier.getValue().getAsJsonArray()) {
					JsonObject preset = entry.getAsJsonObject();
					JsonObject lods = BedrockJson.optionalObject(preset, "lods");
					if (lods == null) {
						lods = new JsonObject();
					}
					presets.add(new Preset(number, BedrockJson.string(preset, "name", "Preset"),
							BedrockJson.string(lods, "shadows", "ultra"), BedrockJson.string(lods, "reflections", "ultra"),
							BedrockJson.string(lods, "volumetric_fog", "ultra"), BedrockJson.string(lods, "clouds", "ultra"),
							BedrockJson.string(lods, "block_lights", "off"), BedrockJson.bool(lods, "bloom", true),
							BedrockJson.string(lods, "upscaling_mode", "bilinear"),
							BedrockJson.string(lods, "target_resolution", ""),
							(int) BedrockJson.number(lods, "default_deferred_distance", 0)));
				}
			}
		}
		presets.sort((a, b) -> a.tier() != b.tier() ? Integer.compare(a.tier(), b.tier()) : 0);
		Map<String, Shadows> shadows = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> level : shadowFile.entrySet()) {
			if (level.getValue().isJsonObject()) {
				shadows.put(level.getKey(), shadows(level.getValue().getAsJsonObject()));
			}
		}
		Map<String, Reflections> reflections = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> level : reflectionFile.entrySet()) {
			if (level.getValue().isJsonObject()) {
				reflections.put(level.getKey(), reflections(level.getValue().getAsJsonObject()));
			}
		}
		Map<String, VolumetricFog> fog = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> level : fogFile.entrySet()) {
			if (level.getValue().isJsonObject()) {
				JsonObject f = level.getValue().getAsJsonObject();
				fog.put(level.getKey(), new VolumetricFog((int) BedrockJson.number(f, "tile_width", 8),
						(int) BedrockJson.number(f, "tile_height", 8), (int) BedrockJson.number(f, "depth_slices", 96),
						BedrockJson.number(f, "near_plane", 0.025f), BedrockJson.number(f, "far_plane", 250),
						BedrockJson.number(f, "shadow_pcf_scale", 1)));
			}
		}
		return new QualitySettings(presets, shadows, reflections, fog);
	}

	private static Shadows shadows(JsonObject level) {
		List<Cascade> cascades = new ArrayList<>();
		JsonArray outer = level.has("cascades") && level.get("cascades").isJsonArray() ? level.getAsJsonArray("cascades")
				: new JsonArray();
		// "cascades" holds one array of cascades (per light, as far as the files show).
		JsonArray list = outer.size() > 0 && outer.get(0).isJsonArray() ? outer.get(0).getAsJsonArray() : outer;
		for (JsonElement element : list) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject c = element.getAsJsonObject();
			cascades.add(new Cascade(BedrockJson.number(c, "bias", 0), BedrockJson.number(c, "slope_bias", 0),
					(int) BedrockJson.number(c, "pcf_width", 1), BedrockJson.number(c, "range", 1),
					(int) BedrockJson.number(c, "resolution", 2048), (int) BedrockJson.number(c, "update_frequency", 1),
					(int) BedrockJson.number(c, "update_offset", 0)));
		}
		return new Shadows(List.copyOf(cascades), BedrockJson.number(level, "normal_offset_strength", 0),
				BedrockJson.number(level, "max_shadow_distance", 128),
				BedrockJson.number(level, "cloud_shadow_contribution", 0),
				BedrockJson.number(level, "cloud_shadow_pcf_width", 1), BedrockJson.bool(level, "sun_shadows", true),
				BedrockJson.bool(level, "moon_shadows", true), BedrockJson.bool(level, "cloud_shadows", true));
	}

	private static Reflections reflections(JsonObject level) {
		JsonObject ibl = BedrockJson.optionalObject(level, "ibl");
		if (ibl == null) {
			ibl = new JsonObject();
		}
		JsonObject ssr = BedrockJson.optionalObject(level, "ssr");
		if (ssr == null) {
			ssr = new JsonObject();
		}
		return new Reflections((int) BedrockJson.number(ibl, "num_samples", 64),
				(int) BedrockJson.number(ibl, "dimension", 512), BedrockJson.bool(level, "ssr_enabled", true),
				(int) BedrockJson.number(ssr, "linear_search_step_count", 800),
				(int) BedrockJson.number(ssr, "binary_search_step_count", 40), BedrockJson.number(ssr, "thickness", 0.025f),
				BedrockJson.number(ssr, "fading_distance", 0.75f), BedrockJson.number(ssr, "roughness_cutoff", 0.4f),
				BedrockJson.number(ssr, "roughness_fade_start", 0.3f), BedrockJson.number(ssr, "ssr_scale", 1));
	}

	/** Every preset, tier by tier (Performance before Quality as the file lists them). */
	public List<Preset> presets() {
		return presets;
	}

	/** The preset of that tier with that name (case ignored). */
	public Optional<Preset> preset(int tier, String name) {
		return presets.stream().filter(p -> p.tier() == tier && p.name().equalsIgnoreCase(name)).findFirst();
	}

	public Optional<Shadows> shadows(String level) {
		return Optional.ofNullable(shadows.get(level));
	}

	public Optional<Reflections> reflections(String level) {
		return Optional.ofNullable(reflections.get(level));
	}

	public Optional<VolumetricFog> volumetricFog(String level) {
		return Optional.ofNullable(fog.get(level));
	}
}
