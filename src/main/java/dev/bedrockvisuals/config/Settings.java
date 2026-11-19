package dev.bedrockvisuals.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.raytrace.RtEmissive;
import dev.bedrockvisuals.raytrace.RtScene;
import dev.bedrockvisuals.raytrace.RtShadows;
import dev.bedrockvisuals.render.OfflineRender;
import dev.bedrockvisuals.render.TemporalAA;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's settings, kept in {@code config\bedrockvisuals\settings.json} and changed in Sodium's video
 * settings (SodiumOptions) or with /bv commands. Bedrock's quality preset keeps its own file (Quality).
 *
 * <p>The settings live in the classes that use them; this reads them from there when saving and puts them back
 * at startup, so a command and the menu always agree.
 */
public final class Settings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file;

	/** The file's contents. Fields missing from an older file keep these defaults. */
	private static final class Stored {
		Boolean temporalAntiAliasing;
		String shadows = "maps";
		float sunRadius = 0.27f;
		String light = "off";
		float lightGain = 10f;
		float lightFloor = 0.35f;
		boolean entityLights = true;
		boolean bounce;
		float bounceGain = 1f;
		int samples = 1;
		int exportSamples = 4;
		int exportSubframes = 0;
	}

	private Settings() {
	}

	/** At startup, after the quality preset (which sets anti-aliasing its own way unless the player chose). */
	public static void load(Path configDir) {
		file = configDir.resolve("bedrockvisuals").resolve("settings.json");
		Stored stored = new Stored();
		try {
			if (Files.isRegularFile(file)) {
				Stored read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Stored.class);
				if (read != null) {
					stored = read;
				}
			}
		} catch (IOException | RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[settings] couldn't read {}; using the defaults", file, e);
		}
		if (stored.temporalAntiAliasing != null) {
			TemporalAA.setEnabled(stored.temporalAntiAliasing);
		}
		RtShadows.setMode("traced".equals(stored.shadows) ? RtShadows.Mode.ON : RtShadows.Mode.OFF);
		RtShadows.setSunRadius(stored.sunRadius);
		RtEmissive.setMode(switch (String.valueOf(stored.light)) {
			case "replace" -> RtEmissive.Mode.REPLACE;
			case "floor" -> RtEmissive.Mode.FLOOR;
			default -> RtEmissive.Mode.OFF;
		});
		RtEmissive.setGain(stored.lightGain);
		RtEmissive.setFloor(stored.lightFloor);
		RtEmissive.setEntityLights(stored.entityLights);
		RtEmissive.setBounce(stored.bounce);
		RtEmissive.setBounceGain(stored.bounceGain);
		RtEmissive.setSamples(stored.samples);
		RtEmissive.setOfflineSamples(stored.exportSamples);
		if (stored.exportSubframes > 0) {
			OfflineRender.setSamples(stored.exportSubframes);
		}
		releaseSceneIfUnused();
		BedrockVisualsClient.LOGGER.info("[settings] shadows {}, light {}, bounce {}, entity lights {}", stored.shadows,
				stored.light, stored.bounce, stored.entityLights);
	}

	/** Writes the settings as they are now. */
	public static void save() {
		releaseSceneIfUnused();
		if (file == null) {
			return;
		}
		Stored stored = new Stored();
		stored.temporalAntiAliasing = TemporalAA.isEnabled();
		stored.shadows = RtShadows.mode() == RtShadows.Mode.ON ? "traced" : "maps";
		stored.sunRadius = RtShadows.sunRadius();
		stored.light = RtEmissive.mode().name().toLowerCase(java.util.Locale.ROOT);
		stored.lightGain = RtEmissive.gain();
		stored.lightFloor = RtEmissive.floor();
		stored.entityLights = RtEmissive.entityLights();
		stored.bounce = RtEmissive.bounce();
		stored.bounceGain = RtEmissive.bounceGain();
		stored.samples = RtEmissive.samples();
		stored.exportSamples = RtEmissive.offlineSamples();
		stored.exportSubframes = OfflineRender.samples();
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(stored), StandardCharsets.UTF_8);
		} catch (IOException e) {
			BedrockVisualsClient.LOGGER.warn("[settings] couldn't save {}", file, e);
		}
	}

	/** With every ray-traced feature off, the scene (memory for every section, a build a frame) goes too. */
	private static void releaseSceneIfUnused() {
		if (RtShadows.mode() == RtShadows.Mode.OFF && RtEmissive.mode() == RtEmissive.Mode.OFF
				&& !RtEmissive.bounce()) {
			RtScene.setEnabled(false);
		}
	}
}
