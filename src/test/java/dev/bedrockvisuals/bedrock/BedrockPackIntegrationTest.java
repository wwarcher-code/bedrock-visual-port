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

		// Every material map a block texture set points at decodes.
		List<String> undecodable = new java.util.ArrayList<>();
		int decoded = 0;
		for (String name : pack.textureSetNames()) {
			TextureSet set = pack.textureSet(name).orElseThrow();
			if (name.startsWith("blocks/") && set.material().image() != null) {
				try {
					TgaImage.read(set.material().image());
					decoded++;
				} catch (java.io.IOException e) {
					undecodable.add(name + ": " + e.getMessage());
				}
			}
		}
		System.out.println(decoded + " block material maps decoded");
		assertEquals(List.of(), undecodable);

		// A material atlas with every mapped Java texture in a 16x16 grid slot.
		List<String> names = map.javaTextures().stream().sorted().toList();
		int columns = 64;
		int rows = (names.size() + columns - 1) / columns;
		List<MaterialAtlasImage.Slot> slots = new java.util.ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			slots.add(new MaterialAtlasImage.Slot(names.get(i), (i % columns) * 16, (i / columns) * 16, 16, 16));
		}
		MaterialAtlasImage.Result atlas = MaterialAtlasImage.build(columns * 16, rows * 16, 5, slots,
				name -> map.textureSetFor(pack, name), pack.pbrFallback().blocks());
		System.out.println(atlas.filledSlots() + " of " + slots.size() + " atlas slots filled from texture sets");
		assertEquals(List.of(), atlas.problems());
		assertTrue(atlas.filledSlots() > 900);

		// Bedrock's iron block averages metalness 191 and roughness 137 of 255.
		MaterialAtlasImage.Slot iron = slots.get(names.indexOf("iron_block"));
		long metal = 0;
		long rough = 0;
		for (int y = 0; y < 16; y++) {
			for (int x = 0; x < 16; x++) {
				int value = atlas.levels().getFirst()[(iron.y() + y) * atlas.width() + iron.x() + x];
				metal += value >> 16 & 0xFF;
				rough += value & 0xFF;
			}
		}
		assertEquals(191, metal / 256.0, 1);
		assertEquals(137, rough / 256.0, 1);
	}
}
