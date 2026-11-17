package dev.bedrockvisuals.devtools;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.render.DeferredRenderer;
import dev.bedrockvisuals.render.GBufferDebugView;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Locale;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Drives the game from a file, for testing it where no one can type (a headless run, or a measured run on the
 * developer's PC): started only with {@code -Dbedrockvisuals.script=<file>}. Each client tick, lines appended to
 * the file since the last look run in order (only the new bytes are read; a line runs once it ends in a newline):
 * <ul>
 * <li>{@code /command} sends a command (the server's or a client one such as /bv);</li>
 * <li>{@code view <name>} shows a debug view by its enum name ({@code view OFF} for the normal picture);</li>
 * <li>{@code hud on|off}, {@code lighting on|off}, {@code fly on|off} (creative flight, so a teleport stays put);</li>
 * <li>{@code clouds off|fast|fancy}, {@code transparency on|off} (improved transparency) set those options;</li>
 * <li>{@code vsync on|off}, {@code maxfps <n>} (260 for unlimited; also keeps the game from capping its frame
 * rate while no one touches it);</li>
 * <li>{@code move <blocks per second>} flies the player on along where it looks, horizontally, every tick
 * ({@code move 0} stops);</li>
 * <li>{@code reload} reloads the resource packs (and with them the shaders);</li>
 * <li>{@code status <file>} writes the diagnostics report to a file;</li>
 * <li>{@code screenshot <file>} saves the game's picture (as the window shows it) as PNG;</li>
 * <li>{@code frametimes <seconds> <file>} records frame times (FrameTimes) to the file;</li>
 * <li>{@code wait <seconds>} holds the lines after it back for that long;</li>
 * <li>{@code quit} closes the game (saving the world).</li>
 * </ul>
 */
public final class ScriptRunner {
	private static Path script;
	/** Bytes of the file already taken in. */
	private static long position;
	/** The start of a line not yet ended by a newline. */
	private static final StringBuilder partial = new StringBuilder();
	private static final ArrayDeque<String> queued = new ArrayDeque<>();
	private static ByteBuffer buffer = ByteBuffer.allocate(4096);
	private static long waitUntil;
	private static double moveSpeed;

	private ScriptRunner() {
	}

	public static void register() {
		String file = System.getProperty("bedrockvisuals.script");
		if (file == null || file.isBlank()) {
			return;
		}
		script = Path.of(file);
		BedrockVisualsClient.LOGGER.info("[script] running lines appended to {}", script);
		ClientTickEvents.START_CLIENT_TICK.register(client -> FrameTimes.tickStart());
		ClientTickEvents.END_CLIENT_TICK.register(ScriptRunner::tick);
	}

	private static void tick(Minecraft minecraft) {
		FrameTimes.tickEnd();
		if (minecraft.player == null) {
			return;
		}
		if (moveSpeed != 0) {
			double yaw = Math.toRadians(minecraft.player.getYRot());
			double perTick = moveSpeed / 20.0;
			minecraft.player.setDeltaMovement(-Math.sin(yaw) * perTick, 0, Math.cos(yaw) * perTick);
		}
		readAppended();
		while (!queued.isEmpty() && System.nanoTime() >= waitUntil) {
			String line = queued.poll();
			try {
				run(minecraft, line);
			} catch (RuntimeException e) {
				BedrockVisualsClient.LOGGER.error("[script] line failed: {}", line, e);
			}
		}
	}

	/** Takes in what was appended to the file since the last look, queueing each completed line. */
	private static void readAppended() {
		long size;
		try {
			size = Files.size(script);
		} catch (IOException e) {
			return; // not there (yet)
		}
		if (size < position) {
			position = 0; // the file was started over
			partial.setLength(0);
		}
		if (size == position) {
			return;
		}
		try (FileChannel channel = FileChannel.open(script, StandardOpenOption.READ)) {
			channel.position(position);
			if (buffer.capacity() < size - position && size - position < 1 << 20) {
				buffer = ByteBuffer.allocate((int) (size - position));
			}
			buffer.clear();
			int read;
			while ((read = channel.read(buffer)) > 0) {
				position += read;
				buffer.flip();
				// Lines are ASCII in practice; a multi-byte character split between reads isn't worth handling.
				partial.append(StandardCharsets.UTF_8.decode(buffer));
				buffer.clear();
			}
		} catch (IOException e) {
			return;
		}
		int newline;
		while ((newline = partial.indexOf("\n")) >= 0) {
			String line = partial.substring(0, newline).strip();
			partial.delete(0, newline + 1);
			if (!line.isEmpty() && !line.startsWith("#")) {
				queued.add(line);
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
			case "vsync" -> minecraft.options.enableVsync().set(argument.equals("on"));
			case "maxfps" -> {
				minecraft.options.framerateLimit().set(Integer.parseInt(argument));
				minecraft.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
			}
			case "fly" -> {
				minecraft.player.getAbilities().flying = argument.equals("on");
				minecraft.player.onUpdateAbilities();
			}
			case "move" -> {
				moveSpeed = Double.parseDouble(argument);
				if (moveSpeed == 0) {
					minecraft.player.setDeltaMovement(0, 0, 0);
				}
			}
			case "wait" -> waitUntil = System.nanoTime() + (long) (Double.parseDouble(argument) * 1e9);
			case "screenshot" -> screenshot(minecraft, Path.of(argument));
			case "quit" -> minecraft.stop();
			case "frametimes" -> {
				String[] args = argument.split("\\s+", 2);
				FrameTimes.start(Double.parseDouble(args[0]), Path.of(args[1]));
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

	private static void screenshot(Minecraft minecraft, Path file) {
		Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget(), (NativeImage image) -> {
			try (image) {
				Path parent = file.toAbsolutePath().getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				image.writeToFile(file);
				BedrockVisualsClient.LOGGER.info("[script] saved {}", file);
			} catch (IOException e) {
				BedrockVisualsClient.LOGGER.warn("[script] could not save {}", file, e);
			}
		});
	}
}
