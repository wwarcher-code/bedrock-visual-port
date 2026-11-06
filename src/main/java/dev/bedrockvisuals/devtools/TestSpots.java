package dev.bedrockvisuals.devtools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Camera spots saved per world, so tests return to the same views: "scene" is where /bv scene built the test
 * scene (its camera), and any other name is a view saved with /bv spot (an ocean, a cave...). Kept in
 * config/bedrockvisuals/spots.json under the world's name, so they survive restarts and pulls.
 */
public final class TestSpots {
	public static final String SCENE = "scene";

	/** A saved camera: where the feet are, and where the eyes look. */
	public record Spot(String dimension, double x, double y, double z, float yaw, float pitch) {
		/** The command that puts the player there (and in its dimension). */
		String teleport() {
			return String.format(Locale.ROOT, "execute in %s run tp @s %.3f %.3f %.3f %.2f %.2f", dimension, x, y, z,
					yaw, pitch);
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private TestSpots() {
	}

	/** The spot where the player is now. */
	static Optional<Spot> here() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return Optional.empty();
		}
		return Optional.of(new Spot(player.level().dimension().identifier().toString(), player.getX(), player.getY(),
				player.getZ(), player.getYRot(), player.getXRot()));
	}

	/** This world's spots, in the order they were saved. */
	static Map<String, Spot> all() {
		Map<String, Spot> spots = new LinkedHashMap<>();
		String world = worldKey();
		if (world == null) {
			return spots;
		}
		JsonObject worlds = read();
		if (worlds.has(world)) {
			JsonObject saved = worlds.getAsJsonObject(world);
			for (String name : saved.keySet()) {
				try {
					spots.put(name, GSON.fromJson(saved.get(name), Spot.class));
				} catch (RuntimeException e) {
					BedrockVisualsClient.LOGGER.warn("[testing] ignoring the unreadable spot {} in {}", name, world, e);
				}
			}
		}
		return spots;
	}

	static Optional<Spot> get(String name) {
		return Optional.ofNullable(all().get(name));
	}

	/** Saves (or replaces) a spot for this world. */
	static boolean save(String name, Spot spot) {
		String world = worldKey();
		if (world == null) {
			return false;
		}
		JsonObject worlds = read();
		JsonObject saved = worlds.has(world) ? worlds.getAsJsonObject(world) : new JsonObject();
		saved.add(name, GSON.toJsonTree(spot));
		worlds.add(world, saved);
		write(worlds);
		return true;
	}

	static boolean remove(String name) {
		String world = worldKey();
		JsonObject worlds = read();
		if (world == null || !worlds.has(world) || !worlds.getAsJsonObject(world).has(name)) {
			return false;
		}
		worlds.getAsJsonObject(world).remove(name);
		write(worlds);
		return true;
	}

	/** The world's name for singleplayer (its save folder), the server's address otherwise. */
	private static String worldKey() {
		Minecraft minecraft = Minecraft.getInstance();
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			return "save:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		}
		ServerData data = minecraft.getCurrentServer();
		return data == null ? null : "server:" + data.ip;
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("config/bedrockvisuals/spots.json");
	}

	private static JsonObject read() {
		Path file = file();
		if (!Files.isRegularFile(file)) {
			return new JsonObject();
		}
		try {
			return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[testing] could not read {}; starting afresh", file, e);
			return new JsonObject();
		}
	}

	private static void write(JsonObject worlds) {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(worlds), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BedrockVisualsClient.LOGGER.warn("[testing] could not save {}", file, e);
		}
	}
}
