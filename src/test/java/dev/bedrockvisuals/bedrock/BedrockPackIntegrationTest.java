package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Loads a real Bedrock pack. Skipped unless Gradle gets -PbedrockPack=&lt;folder&gt;: an install's
 * resource_packs folder (its vanilla stack is loaded), or a single pack such as resource_pack in Mojang's
 * bedrock-samples.
 */
class BedrockPackIntegrationTest {
	@Test
	void loadsAVanillaPackWithoutProblems() {
		String folder = System.getProperty("bedrockPack", "");
		assumeTrue(!folder.isEmpty(), "no -PbedrockPack given");

		Path path = Path.of(folder);
		List<Path> packs = java.nio.file.Files.isDirectory(path.resolve("vanilla"))
				? BedrockInstall.vanillaStack(path) : List.of(path);
		BedrockPack pack = BedrockPack.load(packs);
		System.out.println(pack.summary());
		pack.problems().forEach(System.out::println);
		assertEquals(List.of(), pack.problems());

		ClientBiome desert = pack.biome("minecraft:desert");
		assertEquals("minecraft:desert_lighting", desert.lighting());
		assertTrue(pack.lighting(desert.lighting()).sun().illuminance().sample(0) > 0);

		TextureMap map = TextureMap.load();
		long found = List.of("oak_log", "red_wool", "stone", "oak_planks", "glass")
				.stream().filter(t -> map.textureSetFor(pack, t).isPresent()).count();
		assertEquals(5, found);
	}
}
