package dev.bedrockvisuals.bedrock;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * Everything Vibrant Visuals reads from one or more Bedrock resource packs, layered in order: a later pack
 * replaces an earlier pack's file with the same identifier or texture name, as Bedrock's pack stack does.
 *
 * <p>Loading never fails on a single bad file; it is skipped and listed in {@link #problems()}.
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

	/** @param packRoots resource pack folders (each holding manifest.json), lowest priority first */
	public static BedrockPack load(List<Path> packRoots) {
		BedrockPack pack = new BedrockPack();
		for (Path root : packRoots) {
			pack.readPack(root);
		}
		pack.resolveSettings();
		return pack;
	}

	private void readPack(Path root) {
		for (SettingsKind kind : SettingsKind.values()) {
			for (Path file : jsonFiles(root.resolve(kind.folder), false)) {
				read(file, json -> {
					JsonObject settings = BedrockJson.object(json, kind.rootKey);
					String id = BedrockJson.string(BedrockJson.object(settings, "description"), "identifier", null);
					if (id == null) {
						throw new com.google.gson.JsonParseException("no description.identifier");
					}
					rawSettings.get(kind).put(id, settings);
				});
			}
		}
		for (Path file : jsonFiles(root.resolve("biomes"), false)) {
			if (file.getFileName().toString().endsWith(".client_biome.json")) {
				read(file, json -> {
					JsonObject biome = BedrockJson.object(json, ClientBiome.KEY);
					String id = BedrockJson.string(BedrockJson.object(biome, "description"), "identifier", null);
					biomes.put(id, ClientBiome.parse(id, biome));
				});
			}
		}
		Path textures = root.resolve("textures");
		for (Path file : jsonFiles(textures, true)) {
			String fileName = file.getFileName().toString();
			if (fileName.endsWith(TEXTURE_SET_SUFFIX)) {
				String name = textures.relativize(file).toString().replace('\\', '/');
				name = name.substring(0, name.length() - TEXTURE_SET_SUFFIX.length());
				String finalName = name;
				read(file, json -> textureSets.put(finalName,
						TextureSet.parse(finalName, file.getParent(), BedrockJson.object(json, TextureSet.KEY))));
			}
		}
		Path localLighting = root.resolve("local_lighting/local_lighting.json");
		Path pointLights = root.resolve("point_lights/global.json");
		Path lightsFile = Files.isRegularFile(localLighting) ? localLighting : pointLights;
		if (Files.isRegularFile(lightsFile)) {
			read(lightsFile, json -> localLights = LocalLights.parse(json));
		}
		Path pbr = root.resolve("pbr/global.json");
		if (Files.isRegularFile(pbr)) {
			read(pbr, json -> pbrFallback = PbrFallback.parse(json));
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

	private void read(Path file, JsonConsumer consumer) {
		try {
			consumer.accept(BedrockJson.read(file));
		} catch (IOException | RuntimeException e) {
			problems.add(file + ": " + e.getMessage());
		}
	}

	private static List<Path> jsonFiles(Path folder, boolean recursive) {
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = recursive ? Files.walk(folder) : Files.list(folder)) {
			return files.filter(f -> f.toString().endsWith(".json") && Files.isRegularFile(f)).sorted().toList();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
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
