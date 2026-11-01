package dev.bedrockvisuals.devtools;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * The test scene, built the same way in Java (/bv scene) and in Bedrock (the reference pack's
 * /function bv/scene, generated from this class), so screenshots from the two editions compare like with like.
 *
 * <p>Every command is relative to the player's block and the scene always faces north, whichever way the
 * player looks: the last command snaps the player to the centre of their block, looking north and slightly
 * down at it. From there, left to right: a water pool, a stone-brick pillar with a torch, a roofed overhang
 * that shades the ground beneath it, and an oak tree; in front, a row of material samples. The sun crosses
 * from east to west, so shadows fall across the view. Needs cheats on.
 */
public final class TestScene {
	public enum Edition { JAVA, BEDROCK }

	/** The material sample row, left to right. */
	private static final String[] SAMPLES = {"iron_block", "gold_block", "copper_block", "diamond_block",
			"emerald_block", "glowstone", "sea_lantern", "glass", "oak_leaves", "polished_andesite", "oak_planks"};
	/** How far north of the player the scene's front row starts. */
	static final int DISTANCE = 9;
	/** The camera's pitch, in degrees below the horizon. */
	static final int PITCH = 15;

	private TestScene() {
	}

	/** The commands that build the scene around the player and point the camera at it. */
	public static List<String> commands(Edition edition) {
		List<String> commands = new ArrayList<>();
		// Animals wandering into shots.
		commands.add(edition == Edition.JAVA ? "kill @e[type=!player,distance=..64]" : "kill @e[type=!player,r=64]");
		// Clear from the player to the back of the scene, 13 wide, and lay flat grass.
		commands.add(fill(edition, -6, 0, -DISTANCE, 6, 9, 11, "air"));
		commands.add(fill(edition, -6, -1, -DISTANCE, 6, -1, 11, "grass_block"));
		// Material samples in a row at the front.
		for (int i = 0; i < SAMPLES.length; i++) {
			commands.add(set(edition, i - 5, 0, 1, SAMPLES[i]));
		}
		// Water pool, left.
		commands.add(fill(edition, -6, -1, 5, -4, -1, 8, "water"));
		// Pillar with a torch beside it.
		commands.add(fill(edition, -2, 0, 6, -2, 5, 6, "stone_bricks"));
		commands.add(set(edition, -1, 0, 5, "torch"));
		// Roofed overhang on two posts.
		commands.add(fill(edition, 1, 0, 7, 1, 2, 7, "oak_log"));
		commands.add(fill(edition, 3, 0, 7, 3, 2, 7, "oak_log"));
		commands.add(fill(edition, 0, 3, 5, 4, 3, 9, "oak_planks"));
		// Small tree, right.
		commands.add(fill(edition, 4, 4, 9, 6, 6, 11, "oak_leaves"));
		commands.add(fill(edition, 5, 0, 10, 5, 5, 10, "oak_log"));
		// Stand in the middle of the block, looking north (yaw 180) and down at the scene.
		commands.add("execute align xyz run tp @s ~0.5 ~ ~0.5 180 " + PITCH);
		return commands;
	}

	static void build() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) {
			return;
		}
		for (String command : commands(Edition.JAVA)) {
			minecraft.player.connection.sendCommand(command);
		}
	}

	/**
	 * A block as each edition writes it. Leaves are made persistent so the sample, which has no log nearby,
	 * doesn't decay; the editions name that state differently.
	 */
	private static String block(Edition edition, String name) {
		if (name.equals("oak_leaves")) {
			return edition == Edition.JAVA ? "oak_leaves[persistent=true]" : "oak_leaves [\"persistent_bit\"=true]";
		}
		return name;
	}

	/**
	 * A position relative to the player: {@code x} to the east, {@code y} up, {@code z} north from the front
	 * row, which is {@link #DISTANCE} blocks north of the player.
	 */
	private static String at(int x, int y, int z) {
		return rel(x) + " " + rel(y) + " " + rel(-(DISTANCE + z));
	}

	private static String rel(int offset) {
		return offset == 0 ? "~" : "~" + offset;
	}

	private static String set(Edition edition, int x, int y, int z, String block) {
		return "setblock " + at(x, y, z) + " " + block(edition, block);
	}

	private static String fill(Edition edition, int x1, int y1, int z1, int x2, int y2, int z2, String block) {
		return "fill " + at(x1, y1, z1) + " " + at(x2, y2, z2) + " " + block(edition, block);
	}
}
