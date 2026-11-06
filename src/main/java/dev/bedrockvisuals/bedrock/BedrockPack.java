package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Everything Vibrant Visuals reads from one or more Bedrock resource packs, layered in order: a later pack
 * replaces an earlier pack's file with the same identifier or texture name, as Bedrock's pack stack does.
 *
 * <p>Installed packs bundle their JSON into .brarchive files; those are read like loose files. Loading
 * never fails on a single bad file; it is skipped and listed in {@link #problems()}.
 */
public final class BedrockPack {
	private static final String TEXTURE_SET_SUFFIX = ".texture_set.json";

	private final Map<SettingsKind, Map<String, JsonObject>> rawSettings = new EnumMap<>(SettingsKind.class);
	private final Map<String, LightingSettings> lighting = new HashMap<>();
	private final Map<String, AtmosphereSettings> atmosphere = new HashMap<>();
	private final Map<String, ColorGradingSettings> colorGrading = new HashMap<>();
	private final Map<String, WaterSettings> water = new HashMap<>();
	private final Map<String, FogSettings> fog = new HashMap<>();
	private final Map<String, ClientBiome> biomes = new HashMap<>();
	private final Map<String, TextureSet> textureSets = new HashMap<>();
	private LocalLights localLights = LocalLights.NONE;
	private PbrFallback pbrFallback = PbrFallback.DEFAULT;
	private final List<String> problems = new ArrayList<>();

	private BedrockPack() {
		for (SettingsKind kind : SettingsKind.values()) {
			rawSettings.put(kind, new HashMap<>());
		}
	}

	private PackFiles files;

	/** The pack's image file for a path without extension (textures/environment/sun), highest pack first. */
	public java.util.Optional<java.nio.file.Path> image(String pathWithoutExtension) {
		return files == null ? java.util.Optional.empty() : files.image(pathWithoutExtension);
	}

	/** @param packRoots resource pack folders (each holding manifest.json), lowest priority first */
	public static BedrockPack load(List<Path> packRoots) {
		BedrockPack pack = new BedrockPack();
		PackFiles files = PackFiles.of(packRoots, pack.problems);
		pack.files = files;
		pack.read(files);
		pack.resolveSettings();
		return pack;
	}

	private void read(PackFiles files) {
		for (SettingsKind kind : SettingsKind.values()) {
			for (String path : jsonFiles(files, kind.folder, false)) {
				read(files, path, json -> {
					JsonObject settings = BedrockJson.object(json, kind.rootKey);
					String id = BedrockJson.string(BedrockJson.object(settings, "description"), "identifier", null);
					if (id == null) {
						throw new com.google.gson.JsonParseException("no description.identifier");
					}
					rawSettings.get(kind).put(id, settings);
				});
			}
		}
		for (String path : jsonFiles(files, "biomes", false)) {
			if (path.endsWith(".client_biome.json")) {
				read(files, path, json -> {
					JsonObject biome = BedrockJson.object(json, ClientBiome.KEY);
					String id = BedrockJson.string(BedrockJson.object(biome, "description"), "identifier", null);
					biomes.put(id, ClientBiome.parse(id, biome));
				});
			}
		}
		for (String path : jsonFiles(files, "textures", true)) {
			if (path.endsWith(TEXTURE_SET_SUFFIX)) {
				// Named by path under textures/, e.g. "blocks/stone".
				String name = path.substring("textures/".length(), path.length() - TEXTURE_SET_SUFFIX.length());
				String folder = path.substring(0, path.lastIndexOf('/') + 1);
				read(files, path, json -> textureSets.put(name, TextureSet.parse(name,
						base -> files.image(folder + base), BedrockJson.object(json, TextureSet.KEY))));
			}
		}
		String lights = files.exists("local_lighting/local_lighting.json")
				? "local_lighting/local_lighting.json" : "point_lights/global.json";
		if (files.exists(lights)) {
			read(files, lights, json -> localLights = LocalLights.parse(json));
		}
		if (files.exists("pbr/global.json")) {
			read(files, "pbr/global.json", json -> pbrFallback = PbrFallback.parse(json));
		}
	}

	private void resolveSettings() {
		resolve(SettingsKind.LIGHTING, lighting, LightingSettings::parse);
		resolve(SettingsKind.ATMOSPHERE, atmosphere, AtmosphereSettings::parse);
		resolve(SettingsKind.COLOR_GRADING, colorGrading, ColorGradingSettings::parse);
		resolve(SettingsKind.WATER, water, WaterSettings::parse);
		resolve(SettingsKind.FOG, fog, FogSettings::parse);
		for (ClientBiome biome : biomes.values()) {
			checkReference(biome, SettingsKind.LIGHTING, biome.lighting());
			checkReference(biome, SettingsKind.ATMOSPHERE, biome.atmosphere());
			checkReference(biome, SettingsKind.COLOR_GRADING, biome.colorGrading());
			checkReference(biome, SettingsKind.WATER, biome.water());
			checkReference(biome, SettingsKind.FOG, biome.fog());
		}
	}

	/** Parses each file merged over its kind's default, so partial files (most fogs) inherit the rest. */
	private <T> void resolve(SettingsKind kind, Map<String, T> into, BiFunction<String, JsonObject, T> parser) {
		Map<String, JsonObject> raw = rawSettings.get(kind);
		JsonObject base = raw.getOrDefault(kind.defaultId, new JsonObject());
		if (!raw.containsKey(kind.defaultId)) {
			problems.add("no " + kind.defaultId + " in any pack; " + kind.folder + " settings have no defaults");
		}
		for (Map.Entry<String, JsonObject> entry : raw.entrySet()) {
			String id = entry.getKey();
			try {
				JsonObject merged = id.equals(kind.defaultId) ? entry.getValue() : BedrockJson.merge(base, entry.getValue());
				into.put(id, parser.apply(id, merged));
			} catch (RuntimeException e) {
				problems.add(kind.folder + " " + id + ": " + e.getMessage());
			}
		}
	}

	private void checkReference(ClientBiome biome, SettingsKind kind, String id) {
		if (!rawSettings.get(kind).containsKey(id)) {
			problems.add("biome " + biome.identifier() + " uses missing " + kind.folder + " " + id);
		}
	}

	private interface JsonConsumer {
		void accept(JsonObject json) throws IOException;
	}

	private void read(PackFiles files, String path, JsonConsumer consumer) {
		try {
			consumer.accept(files.json(path));
		} catch (IOException | RuntimeException e) {
			problems.add(path + ": " + e.getMessage());
		}
	}

	private static List<String> jsonFiles(PackFiles files, String folder, boolean recursive) {
		return files.list(folder, recursive).stream().filter(path -> path.endsWith(".json")).toList();
	}

	public List<String> problems() {
		return List.copyOf(problems);
	}

	/** The biome's settings references; the vanilla defaults for a biome no pack describes. */
	public ClientBiome biome(String identifier) {
		return biomes.getOrDefault(identifier, ClientBiome.DEFAULT);
	}

	public LightingSettings lighting(String identifier) {
		return lookup(lighting, identifier, SettingsKind.LIGHTING);
	}

	public AtmosphereSettings atmosphere(String identifier) {
		return lookup(atmosphere, identifier, SettingsKind.ATMOSPHERE);
	}

	public ColorGradingSettings colorGrading(String identifier) {
		return lookup(colorGrading, identifier, SettingsKind.COLOR_GRADING);
	}

	public WaterSettings water(String identifier) {
		return lookup(water, identifier, SettingsKind.WATER);
	}

	public FogSettings fog(String identifier) {
		return lookup(fog, identifier, SettingsKind.FOG);
	}

	private static <T> T lookup(Map<String, T> settings, String identifier, SettingsKind kind) {
		T value = settings.get(identifier);
		return value != null ? value : settings.get(kind.defaultId);
	}

	/** @param name path under textures/ without extension, e.g. "blocks/stone" */
	public Optional<TextureSet> textureSet(String name) {
		return Optional.ofNullable(textureSets.get(name));
	}

	/** Every loaded texture set's name, e.g. "blocks/stone". */
	public java.util.Set<String> textureSetNames() {
		return java.util.Collections.unmodifiableSet(textureSets.keySet());
	}

	public LocalLights localLights() {
		return localLights;
	}

	public PbrFallback pbrFallback() {
		return pbrFallback;
	}

	public String summary() {
		return String.format("%d texture sets, %d biomes, %d lighting, %d atmosphere, %d color grading, %d water, "
						+ "%d fog, %d local lights, %d problems",
				textureSets.size(), biomes.size(), lighting.size(), atmosphere.size(), colorGrading.size(),
				water.size(), fog.size(), localLights.byBlock().size(), problems.size());
	}
}
