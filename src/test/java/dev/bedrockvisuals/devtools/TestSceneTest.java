package dev.bedrockvisuals.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.bedrockvisuals.devtools.TestScene.Edition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestSceneTest {
	/** The Bedrock pack's scene function, generated from {@link TestScene}. */
	static final Path BEDROCK_SCENE = Path.of("bedrock/test-pack/functions/bv/scene.mcfunction");

	@Test
	void theSceneIsNorthOfThePlayerWithTheTreeToTheEast() {
		List<String> commands = TestScene.commands(Edition.JAVA);
		// Cleared from the player's block to the back of the scene, 6 either side; north is -Z.
		assertEquals("fill ~-6 ~ ~ ~6 ~9 ~-20 air", commands.get(1));
		assertEquals("fill ~-6 ~-1 ~ ~6 ~-1 ~-20 grass_block", commands.get(2));
		// The tree's trunk is on the right, which is east (+X) looking north.
		assertTrue(commands.contains("fill ~5 ~ ~-19 ~5 ~5 ~-19 oak_log"), commands.toString());
		// The camera command comes last, since it moves the player the other commands are relative to.
		assertEquals("execute align xyz run tp @s ~0.5 ~ ~0.5 180 15", commands.getLast());
	}

	@Test
	void editionsDifferOnlyInSyntax() {
		List<String> java = TestScene.commands(Edition.JAVA);
		List<String> bedrock = TestScene.commands(Edition.BEDROCK);
		assertEquals(java.size(), bedrock.size());
		assertTrue(java.contains("setblock ~3 ~ ~-10 oak_leaves[persistent=true]"), java.toString());
		assertTrue(bedrock.contains("setblock ~3 ~ ~-10 oak_leaves [\"persistent_bit\"=true]"), bedrock.toString());
	}

	/** Run with -DupdateBedrockPack=true (or -PupdateBedrockPack) to rewrite the file after changing the scene. */
	@Test
	void bedrockPackMatchesTheScene() throws IOException {
		String expected = bedrockFunction();
		if (Boolean.getBoolean("updateBedrockPack")) {
			Files.writeString(BEDROCK_SCENE, expected, StandardCharsets.UTF_8);
		}
		assertEquals(expected, Files.readString(BEDROCK_SCENE, StandardCharsets.UTF_8),
				"the Bedrock pack is out of date; run the tests with -PupdateBedrockPack");
	}

	static String bedrockFunction() {
		StringBuilder text = new StringBuilder("""
				# Generated from TestScene.java by TestSceneTest: builds the Bedrock Visuals test scene north of you and
				# points the camera at it, the same as /bv scene in Java. Stops the clock, weather and mob spawning.
				gamerule dodaylightcycle false
				gamerule doweathercycle false
				gamerule domobspawning false
				weather clear
				time set 6000
				""");
		for (String command : TestScene.commands(Edition.BEDROCK)) {
			text.append(command).append('\n');
		}
		return text.toString();
	}
}
