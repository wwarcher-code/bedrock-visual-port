package dev.bedrockvisuals.devtools;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * /bv scene: builds the same test scene in front of the player every time, so test rounds compare like with
 * like. Facing the scene from where it's built, from left to right: a water pool, a stone-brick pillar with a
 * torch, a roofed overhang that shades the ground beneath it, and an oak tree; in front, a row of material
 * samples (metals, gems, glowing blocks, glass, leaves and plain blocks). Needs cheats on.
 */
final class TestScene {
	/** The material sample row, left to right. */
	private static final String[] SAMPLES = {"iron_block", "gold_block", "copper_block", "diamond_block",
			"emerald_block", "glowstone", "sea_lantern", "glass", "oak_leaves", "polished_andesite", "oak_planks"};

	private TestScene() {
	}

	/** The commands that build the scene for a player standing at {@code feet} facing {@code facing}. */
	static List<String> commands(BlockPos feet, Direction facing) {
		Direction right = facing.getClockWise();
		// The scene's origin sits on the ground three blocks ahead of the player.
		BlockPos origin = feet.relative(facing, 3);
		List<String> commands = new ArrayList<>();
		// Clear the space and lay flat grass, 13 wide and 12 deep.
		commands.add(fill(origin, right, facing, -6, 0, 0, 6, 9, 11, "air"));
		commands.add(fill(origin, right, facing, -6, -1, 0, 6, -1, 11, "grass_block"));
		// Material samples in a row at the front.
		for (int i = 0; i < SAMPLES.length; i++) {
			commands.add(set(origin, right, facing, i - 5, 0, 1, SAMPLES[i]));
		}
		// Water pool, left.
		commands.add(fill(origin, right, facing, -6, -1, 5, -4, -1, 8, "water"));
		// Pillar with a torch beside it.
		commands.add(fill(origin, right, facing, -2, 0, 6, -2, 5, 6, "stone_bricks"));
		commands.add(set(origin, right, facing, -1, 0, 5, "torch"));
		// Roofed overhang on two posts.
		commands.add(fill(origin, right, facing, 1, 0, 7, 1, 2, 7, "oak_log"));
		commands.add(fill(origin, right, facing, 3, 0, 7, 3, 2, 7, "oak_log"));
		commands.add(fill(origin, right, facing, 0, 3, 5, 4, 3, 9, "oak_planks"));
		// Small tree, right.
		commands.add(fill(origin, right, facing, 4, 4, 9, 6, 6, 11, "oak_leaves"));
		commands.add(fill(origin, right, facing, 5, 0, 10, 5, 5, 10, "oak_log"));
		return commands;
	}

	static void build() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		for (String command : commands(minecraft.player.blockPosition(), minecraft.player.getDirection())) {
			minecraft.player.connection.sendCommand(command);
		}
	}

	private static BlockPos at(BlockPos origin, Direction right, Direction forward, int x, int y, int z) {
		return origin.relative(right, x).relative(forward, z).above(y);
	}

	private static String set(BlockPos origin, Direction right, Direction forward, int x, int y, int z, String block) {
		BlockPos pos = at(origin, right, forward, x, y, z);
		return "setblock " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + block;
	}

	private static String fill(BlockPos origin, Direction right, Direction forward, int x1, int y1, int z1, int x2,
			int y2, int z2, String block) {
		BlockPos a = at(origin, right, forward, x1, y1, z1);
		BlockPos b = at(origin, right, forward, x2, y2, z2);
		return "fill " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " "
				+ b.getZ() + " " + block;
	}
}
