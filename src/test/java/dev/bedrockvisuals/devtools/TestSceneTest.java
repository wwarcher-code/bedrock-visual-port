package dev.bedrockvisuals.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class TestSceneTest {
	@Test
	void facingNorthTheSceneIsAheadAndTheTreeToTheEast() {
		List<String> commands = TestScene.commands(new BlockPos(0, 64, 0), Direction.NORTH);
		// Cleared box: 6 either side, starting 3 ahead (north is -Z) and 12 deep.
		assertEquals("fill -6 64 -3 6 73 -14 air", commands.getFirst());
		assertEquals("fill -6 63 -3 6 63 -14 grass_block", commands.get(1));
		// The tree's trunk is on the right, which is east (+X) when facing north.
		String trunk = commands.getLast();
		assertTrue(trunk.startsWith("fill 5 64 -13 5 69 -13 oak_log"), trunk);
	}

	@Test
	void facingEastTheSceneTurnsWithThePlayer() {
		List<String> commands = TestScene.commands(new BlockPos(0, 64, 0), Direction.EAST);
		// Ahead is +X, right is south (+Z).
		assertEquals("fill 3 64 -6 14 73 6 air", commands.getFirst());
	}
}
