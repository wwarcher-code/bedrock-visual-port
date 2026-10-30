package dev.bedrockvisuals.bedrock;

import dev.bedrockvisuals.BedrockVisualsClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Loads the Bedrock pack and texture map at startup and holds them for the renderer. */
public final class BedrockInputs {
	private static final int PROBLEMS_LOGGED = 20;

	private static volatile BedrockPack pack;
	private static volatile TextureMap textureMap;

	private BedrockInputs() {
	}

	/** Empty when no Bedrock install was found. */
	public static Optional<BedrockPack> pack() {
		return Optional.ofNullable(pack);
	}

	public static TextureMap textureMap() {
		return textureMap;
	}

	public static void load(Path configDir) {
		textureMap = TextureMap.load();
		Path packsFile = configDir.resolve("bedrockvisuals-packs.txt");
		List<Path> packs = BedrockInstall.findPacks(packsFile);
		if (packs.isEmpty()) {
			BedrockVisualsClient.LOGGER.warn("[bedrock] no Bedrock install found; list your pack folders in {}", packsFile);
			return;
		}
		for (Path folder : packs) {
			if (!Files.isDirectory(folder)) {
				BedrockVisualsClient.LOGGER.warn("[bedrock] pack folder {} does not exist", folder);
			}
		}
		BedrockVisualsClient.LOGGER.info("[bedrock] loading packs {}", packs);
		BedrockVisualsClient.LOGGER.info("[bedrock] packs installed beside it: {}", BedrockInstall.siblingPacks(packs.getFirst()));

		long start = System.nanoTime();
		BedrockPack loaded = BedrockPack.load(packs);
		BedrockVisualsClient.LOGGER.info("[bedrock] loaded in {} ms: {}; texture map covers {} Java textures",
				(System.nanoTime() - start) / 1_000_000, loaded.summary(), textureMap.size());
		List<String> problems = loaded.problems();
		problems.stream().limit(PROBLEMS_LOGGED).forEach(p -> BedrockVisualsClient.LOGGER.warn("[bedrock] {}", p));
		if (problems.size() > PROBLEMS_LOGGED) {
			BedrockVisualsClient.LOGGER.warn("[bedrock] ...and {} more problems", problems.size() - PROBLEMS_LOGGED);
		}
		pack = loaded;
	}
}
