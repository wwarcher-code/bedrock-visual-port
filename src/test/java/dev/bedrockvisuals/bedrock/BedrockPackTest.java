package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BedrockPackTest {
	private static final float EPSILON = 1e-5f;

	@TempDir
	Path temp;

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	private static String fog(String id, String distance) {
		return "{\"format_version\": \"1.21.90\", \"minecraft:fog_settings\": {\"description\": {\"identifier\": \""
				+ id + "\"}, \"distance\": " + distance + "}}";
	}

	@Test
	void partialFogsInheritTheDefaultAndLaterPacksWin() throws IOException {
		Path base = temp.resolve("base");
		Path top = temp.resolve("top");
		write(base.resolve("fogs/default.json"), fog("minecraft:fog_default",
				"{\"air\": {\"fog_start\": 0.9, \"fog_end\": 1.0, \"fog_color\": \"#FFFFFF\"}}"));
		write(base.resolve("fogs/beach.json"), fog("minecraft:fog_beach",
				"{\"water\": {\"fog_start\": 0, \"fog_end\": 60, \"fog_color\": \"#14A2C5\"}}"));
		write(top.resolve("fogs/beach.json"), fog("minecraft:fog_beach",
				"{\"water\": {\"fog_start\": 0, \"fog_end\": 30, \"fog_color\": \"#14A2C5\"}}"));

		BedrockPack pack = BedrockPack.load(List.of(base, top));
		FogSettings beach = pack.fog("minecraft:fog_beach");
		assertEquals(30, beach.distance().get("water").end(), EPSILON);
		assertEquals(0.9f, beach.distance().get("air").start(), EPSILON);
		assertEquals(pack.fog("minecraft:fog_default"), pack.fog("minecraft:no_such_fog"));
	}

	@Test
	void badFilesAreReportedNotThrown() throws IOException {
		write(temp.resolve("fogs/broken.json"), "{ not json");
		write(temp.resolve("textures/blocks/stone.texture_set.json"),
				"{\"format_version\": \"1.21.30\", \"minecraft:texture_set\": {\"color\": \"stone\"}}");

		BedrockPack pack = BedrockPack.load(List.of(temp));
		assertTrue(pack.problems().stream().anyMatch(p -> p.contains("broken.json")));
		// stone.png doesn't exist, so the texture set is reported too.
		assertTrue(pack.problems().stream().anyMatch(p -> p.contains("stone.texture_set.json")));
	}

	@Test
	void textureSetsResolveImagesAndAreNamedByPath() throws IOException {
		write(temp.resolve("textures/blocks/huge_fungus/crimson_planks.texture_set.json"),
				"{\"format_version\": \"1.21.30\", \"minecraft:texture_set\": {\"color\": \"crimson_planks\", "
						+ "\"metalness_emissive_roughness_subsurface\": \"crimson_planks_mers\"}}");
		Files.createFile(temp.resolve("textures/blocks/huge_fungus/crimson_planks.png"));
		Files.createFile(temp.resolve("textures/blocks/huge_fungus/crimson_planks_mers.tga"));

		BedrockPack pack = BedrockPack.load(List.of(temp));
		TextureSet set = pack.textureSet("blocks/huge_fungus/crimson_planks").orElseThrow();
		assertTrue(set.hasSubsurface());
		assertTrue(set.material().image().toString().endsWith("crimson_planks_mers.tga"));
	}
}
