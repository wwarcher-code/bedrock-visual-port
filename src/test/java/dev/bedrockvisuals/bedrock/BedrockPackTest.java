package dev.bedrockvisuals.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
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
				"{\"format_version\": \"1.21.30\", \"minecraft:texture_set\": {\"color\": \"stone\", "
						+ "\"metalness_emissive_roughness_subsurface\": \"stone_mers\"}}");

		BedrockPack pack = BedrockPack.load(List.of(temp));
		assertTrue(pack.problems().stream().anyMatch(p -> p.contains("broken.json")));
		// stone_mers doesn't exist, so the texture set is reported too. (A missing color image is fine: block
		// colors come from Java's textures.)
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

	@Test
	void vanillaStackOrdersVersionsNumerically() throws IOException {
		for (String name : List.of("vanilla_1.21.120", "vanilla", "chemistry", "vanilla_1.21.20", "vanilla_base",
				"vanilla_1.26.60", "experimental_y_2026_drop_1")) {
			Files.createDirectories(temp.resolve(name));
		}
		List<String> stack = BedrockInstall.vanillaStack(temp).stream().map(p -> p.getFileName().toString()).toList();
		assertEquals(List.of("vanilla_base", "vanilla", "vanilla_1.21.20", "vanilla_1.21.120", "vanilla_1.26.60"), stack);
	}

	/** Builds a .brarchive as Bedrock writes it. */
	private static byte[] brarchive(String... namesAndContents) {
		int count = namesAndContents.length / 2;
		ByteBuffer table = ByteBuffer.allocate(16 + count * 256).order(ByteOrder.LITTLE_ENDIAN);
		table.putLong(0x267052A0B125277DL).putInt(count).putInt(1);
		ByteArrayOutputStream contents = new ByteArrayOutputStream();
		for (int i = 0; i < count; i++) {
			byte[] name = namesAndContents[2 * i].getBytes(StandardCharsets.UTF_8);
			byte[] content = namesAndContents[2 * i + 1].getBytes(StandardCharsets.UTF_8);
			int descriptor = 16 + i * 256;
			table.put(descriptor, (byte) name.length);
			table.put(descriptor + 1, name);
			table.putInt(descriptor + 248, contents.size());
			table.putInt(descriptor + 252, content.length);
			contents.writeBytes(content);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(table.array());
		out.writeBytes(contents.toByteArray());
		return out.toByteArray();
	}

	@Test
	void readsArchivedJsonWithImagesFromAnotherPack() throws IOException {
		Path base = temp.resolve("vanilla");
		Path versioned = temp.resolve("vanilla_1.26.60");
		Files.createDirectories(base.resolve("textures/blocks"));
		Files.createFile(base.resolve("textures/blocks/stone.png"));
		Files.createFile(base.resolve("textures/blocks/stone_mers.png"));
		Files.createDirectories(versioned.resolve("__brarchive/textures"));
		Files.write(versioned.resolve("__brarchive/textures/blocks.brarchive"), brarchive(
				"stone.texture_set.json",
				"{\"format_version\": \"1.21.30\", \"minecraft:texture_set\": {\"color\": \"stone\", "
						+ "\"metalness_emissive_roughness_subsurface\": \"stone_mers\"}}",
				"empty_stub.json", ""));
		Files.createDirectories(versioned.resolve("__brarchive"));
		Files.write(versioned.resolve("__brarchive/fogs.brarchive"), brarchive(
				"fogs/default_fog_setting.json", fog("minecraft:fog_default", "{}")));

		BedrockPack pack = BedrockPack.load(List.of(base, versioned));
		// Only the missing lighting, atmosphere, grading and water defaults are reported.
		assertTrue(pack.problems().stream().allMatch(p -> p.contains("settings have no defaults")), pack.problems()::toString);
		TextureSet stone = pack.textureSet("blocks/stone").orElseThrow();
		assertTrue(stone.color().image().startsWith(base));
		assertTrue(pack.fog("minecraft:fog_default").distance().isEmpty());
	}

	@Test
	void rejectsFilesThatAreNotArchives() {
		org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
				() -> Brarchive.read("not an archive at all".getBytes(StandardCharsets.UTF_8)));
	}
}
