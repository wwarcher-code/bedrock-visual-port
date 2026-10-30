package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Loads a real Bedrock pack. Skipped unless Gradle gets -PbedrockPack=&lt;folders&gt;, separated by
 * semicolons, lowest priority first: an install's resource_packs folder (its vanilla stack is loaded), or a
 * single pack such as resource_pack in Mojang's bedrock-samples.
 */
class BedrockPackIntegrationTest {
	@Test
	void loadsAVanillaPackWithoutProblems() {
		String folder = System.getProperty("bedrockPack", "");
		assumeTrue(!folder.isEmpty(), "no -PbedrockPack given");

		List<Path> folders = java.util.Arrays.stream(folder.split(";")).map(String::strip).map(Path::of).toList();
		BedrockPack pack = BedrockPack.load(BedrockInstall.expand(folders));
		System.out.println(pack.summary());
		pack.problems().stream().limit(20).forEach(System.out::println);
		if (pack.problems().size() > 20) {
			System.out.println("...and " + (pack.problems().size() - 20) + " more");
		}
		assertEquals(List.of(), pack.problems());

		ClientBiome desert = pack.biome("minecraft:desert");
		assertEquals("minecraft:desert_lighting", desert.lighting());
		assertTrue(pack.lighting(desert.lighting()).sun().illuminance().sample(0) > 0);

		TextureMap map = TextureMap.load();
		long found = List.of("oak_log", "red_wool", "stone", "oak_planks", "glass")
				.stream().filter(t -> map.textureSetFor(pack, t)
						.filter(set -> set.material().isPresent()).isPresent()).count();
		assertEquals(5, found);
	}
}
