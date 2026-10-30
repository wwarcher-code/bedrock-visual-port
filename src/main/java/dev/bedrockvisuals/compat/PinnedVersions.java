package dev.bedrockvisuals.compat;

import dev.bedrockvisuals.BedrockVisualsClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Compares the loaded Sodium, Distant Horizons and Replay Mod versions with the ones pinned in gradle.properties.
 * The hooks target those exact builds, so a mismatch is the first thing to suspect when a hook fails.
 */
public final class PinnedVersions {
	private static final String PINS_FILE = "/bedrockvisuals.pins.properties";

	private PinnedVersions() {
	}

	public static void check() {
		Properties pins = load();
		for (String modId : pins.stringPropertyNames()) {
			String pinned = pins.getProperty(modId);
			FabricLoader.getInstance().getModContainer(modId).ifPresentOrElse(mod -> {
				String loaded = mod.getMetadata().getVersion().getFriendlyString();
				if (loaded.equals(pinned)) {
					BedrockVisualsClient.LOGGER.info("{} {} matches the pinned version", modId, loaded);
				} else {
					BedrockVisualsClient.LOGGER.warn("{} {} is loaded but {} is pinned; its hooks may fail", modId, loaded, pinned);
				}
			}, () -> BedrockVisualsClient.LOGGER.info("{} not loaded; its hooks are disabled", modId));
		}
	}

	private static Properties load() {
		Properties pins = new Properties();
		try (InputStream in = PinnedVersions.class.getResourceAsStream(PINS_FILE)) {
			if (in == null) {
				throw new IllegalStateException(PINS_FILE + " is missing from the mod jar");
			}
			pins.load(in);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return pins;
	}
}
