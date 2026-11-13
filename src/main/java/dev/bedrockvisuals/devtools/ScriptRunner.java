package dev.bedrockvisuals.devtools;

import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.render.DeferredRenderer;
import dev.bedrockvisuals.render.GBufferDebugView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Drives the game from a file, for testing it where no one can type (a headless run): started only with
 * {@code -Dbedrockvisuals.script=<file>}. Each client tick, lines appended to the file since the last look run in
 * order:
 * <ul>
 * <li>{@code /command} sends a command (the server's or a client one such as /bv);</li>
 * <li>{@code view <name>} shows a debug view by its enum name ({@code view OFF} for the normal picture);</li>
 * <li>{@code hud on|off}, {@code lighting on|off}, {@code fly on|off} (creative flight, so a teleport stays put);</li>
 * <li>{@code clouds off|fast|fancy}, {@code transparency on|off} (improved transparency) set those options;</li>
 * <li>{@code reload} reloads the resource packs (and with them the shaders);</li>
 * <li>{@code status <file>} writes the diagnostics report to a file.</li>
 * </ul>
 */
public final class ScriptRunner {
	private static Path script;
	private static int linesRun;

	private ScriptRunner() {
	}

	public static void register() {
		String file = System.getProperty("bedrockvisuals.script");
		if (file == null || file.isBlank()) {
			return;
		}
		script = Path.of(file);
		BedrockVisualsClient.LOGGER.info("[script] running lines appended to {}", script);
		ClientTickEvents.END_CLIENT_TICK.register(ScriptRunner::tick);
	}

	private static void tick(Minecraft minecraft) {
		if (minecraft.player == null || !Files.isRegularFile(script)) {
			return;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(script, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return;
		}
		if (lines.size() < linesRun) {
			linesRun = 0; // the file was started over
		}
		while (linesRun < lines.size()) {
			String line = lines.get(linesRun++).strip();
			if (!line.isEmpty() && !line.startsWith("#")) {
				try {
					run(minecraft, line);
				} catch (RuntimeException e) {
					BedrockVisualsClient.LOGGER.error("[script] line failed: {}", line, e);
				}
			}
		}
	}

	private static void run(Minecraft minecraft, String line) {
		BedrockVisualsClient.LOGGER.info("[script] {}", line);
		if (line.startsWith("/")) {
			minecraft.player.connection.sendCommand(line.substring(1));
			return;
		}
		String[] parts = line.split("\\s+", 2);
		String argument = parts.length > 1 ? parts[1] : "";
		switch (parts[0]) {
			case "view" -> GBufferDebugView.setView(GBufferDebugView.View.valueOf(argument.toUpperCase(Locale.ROOT)));
			case "hud" -> {
				if (minecraft.gui.hud.isHidden() != argument.equals("off")) {
					minecraft.gui.hud.toggle();
				}
			}
			case "lighting" -> DeferredRenderer.setEnabled(argument.equals("on"));
			case "reload" -> minecraft.reloadResourcePacks();
			case "clouds" -> minecraft.options.cloudStatus().set(net.minecraft.client.CloudStatus.valueOf(
					argument.toUpperCase(Locale.ROOT)));
			case "transparency" -> minecraft.options.improvedTransparency().set(argument.equals("on"));
			case "fly" -> {
				minecraft.player.getAbilities().flying = argument.equals("on");
				minecraft.player.onUpdateAbilities();
			}
			case "status" -> {
				DiagnosticsReport report = new DiagnosticsReport("Bedrock Visuals status");
				report.lightingAt("now");
				report.logExcerpt(minecraft.gameDirectory.toPath());
				try {
					Files.writeString(Path.of(argument), report.text(), StandardCharsets.UTF_8);
				} catch (IOException e) {
					BedrockVisualsClient.LOGGER.error("[script] could not write {}", argument, e);
				}
			}
			default -> BedrockVisualsClient.LOGGER.warn("[script] unknown line: {}", line);
		}
	}
}
