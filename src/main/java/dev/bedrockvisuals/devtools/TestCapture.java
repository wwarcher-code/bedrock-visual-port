package dev.bedrockvisuals.devtools;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.render.DeferredRenderer;
import dev.bedrockvisuals.render.AutoExposure;
import dev.bedrockvisuals.render.GBufferDebugView;
import dev.bedrockvisuals.render.GBufferDebugView.View;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * F9 (or /bv capture) runs a whole test round: hides the HUD, clears the weather, and at each test time of
 * day screenshots the deferred lighting, vanilla, the shadow test and the material buffer (plus albedo,
 * normal and light once), then looks up from inside the test scene's pool at noon. It then restores the time and views, stitches a labelled contact sheet, writes a
 * diagnostics report, copies the report to the clipboard and opens the folder.
 *
 * <p>Setting the time uses /time, so the world needs cheats on. Stand still while it runs (about ten
 * seconds).
 */
public final class TestCapture {
	private static final KeyMapping CAPTURE = new KeyMapping("key.bedrockvisuals.capture", InputConstants.KEY_F9,
			KeyMapping.Category.MISC);

	/** Times of day to test, in Java ticks, with labels. */
	private static final long[] TIMES = {6000, 9000, 11500, 18000};
	private static final String[] TIME_LABELS = {"noon", "afternoon", "low sun", "midnight"};
	/** Views captured at every time. */
	private static final View[] VIEWS = {View.OFF, null, View.SHADOWS, View.MATERIAL};
	/** Views captured once, at the first time, in two rows. */
	private static final View[] EXTRA_VIEWS = {View.ALBEDO, View.NORMAL, View.LIGHT, View.LOCAL_EXPOSURE};
	private static final View[] EXTRA_VIEWS_2 = {View.BLOOM, View.CASCADES};
	/** Pitch for the horizon shots: looking 10° up, at the clouds out to the horizon. */
	private static final float HORIZON_PITCH = -10f;
	private static final int TICKS_AFTER_TIME_CHANGE = 15;
	private static final int TICKS_AFTER_VIEW_CHANGE = 3;
	/** Rain's level rises 0.01 a tick: 100 ticks to full, and a little more. */
	private static final int RAIN_FADE_TICKS = 110;

	private static final Deque<Runnable> STEPS = new ArrayDeque<>();
	private static final AtomicInteger PENDING_SHOTS = new AtomicInteger();
	private static int wait;
	private static boolean running;
	private static Path folder;
	private static DiagnosticsReport report;
	private static List<List<ContactSheet.Cell>> sheet;

	// State to restore afterwards.
	private static boolean hudWasHidden;
	private static View viewBefore;
	private static boolean lightingBefore;
	private static long timeBefore;

	private TestCapture() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(CAPTURE);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (CAPTURE.consumeClick()) {
				start();
			}
			if (running) {
				step();
			}
		});
	}

	public static void start() {
		Minecraft minecraft = Minecraft.getInstance();
		if (running) {
			cancel();
			return;
		}
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		running = true;
		folder = minecraft.gameDirectory.toPath().resolve("bedrock-visuals-captures")
				.resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")));
		try {
			Files.createDirectories(folder);
		} catch (IOException e) {
			message("Capture failed: can't create " + folder);
			running = false;
			return;
		}
		report = new DiagnosticsReport("Bedrock Visuals test capture " + folder.getFileName());
		sheet = new ArrayList<>();
		hudWasHidden = minecraft.gui.hud.isHidden();
		viewBefore = GBufferDebugView.view();
		lightingBefore = DeferredRenderer.isEnabled();
		timeBefore = minecraft.level.getDefaultClockTime();
		message("Capturing test views; stand still for about ten seconds.");

		STEPS.clear();
		STEPS.add(() -> {
			if (!minecraft.gui.hud.isHidden()) {
				minecraft.gui.hud.toggle();
			}
			command("weather clear");
		});
		for (int t = 0; t < TIMES.length; t++) {
			long time = TIMES[t];
			String timeLabel = TIME_LABELS[t];
			List<ContactSheet.Cell> row = new ArrayList<>();
			sheet.add(row);
			STEPS.add(() -> {
				command("time set " + time);
				wait = TICKS_AFTER_TIME_CHANGE;
			});
			// The exposure adapts over about a second; rather than wait for it, jump it to the latest
			// measurement (taken well after the new time reached the client), then let a few frames render
			// with it before shooting.
			STEPS.add(() -> {
				AutoExposure.settle();
				wait = TICKS_AFTER_VIEW_CHANGE;
			});
			STEPS.add(() -> report.lightingAt(timeLabel + " (" + time + ")"));
			for (View view : VIEWS) {
				addShot(row, timeLabel, view);
			}
			if (t == 0) {
				List<ContactSheet.Cell> extras = new ArrayList<>();
				for (View view : EXTRA_VIEWS) {
					addShot(extras, timeLabel, view);
				}
				sheet.add(extras);
				List<ContactSheet.Cell> extras2 = new ArrayList<>();
				for (View view : EXTRA_VIEWS_2) {
					addShot(extras2, timeLabel, view);
				}
				sheet.add(extras2);
			}
		}
		addHorizonShots();
		addUnderwaterShots();
		addRainShots();
		STEPS.add(TestCapture::restore);
		STEPS.add(TestCapture::finishWhenSaved);
	}

	/**
	 * At noon, from inside the test scene's pool, looking up through the surface (caustics, the water's
	 * extinction and Snell's window), then back to where the player stood.
	 */
	private static void addUnderwaterShots() {
		Minecraft minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		String back = String.format(java.util.Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f", player.getX(), player.getY(),
				player.getZ(), player.getYRot(), player.getXRot());
		List<ContactSheet.Cell> row = new ArrayList<>();
		sheet.add(row);
		STEPS.add(() -> {
			command("time set 6000");
			command("tp @s " + TestScene.UNDERWATER_VIEW);
			wait = TICKS_AFTER_TIME_CHANGE + 10;
		});
		STEPS.add(() -> {
			AutoExposure.settle();
			wait = TICKS_AFTER_VIEW_CHANGE;
		});
		STEPS.add(() -> report.lightingAt("underwater, noon (6000)"));
		addShot(row, "underwater", View.OFF);
		addShot(row, "underwater", null);
		STEPS.add(() -> command(back));
	}

	/**
	 * At noon in rain, from where the player stands: the grey sky, sun and clouds, and the fog pulled in to the
	 * weather fog (Bedrock: 0.23 to 0.7 of the render distance). Rain fades in at 0.01 a tick, so it waits
	 * for that; the weather is cleared again afterwards.
	 */
	private static void addRainShots() {
		List<ContactSheet.Cell> row = new ArrayList<>();
		sheet.add(row);
		STEPS.add(() -> {
			command("time set 6000");
			command("weather rain");
			wait = RAIN_FADE_TICKS;
		});
		STEPS.add(() -> {
			AutoExposure.settle();
			wait = TICKS_AFTER_VIEW_CHANGE;
		});
		STEPS.add(() -> report.lightingAt("rain, noon (6000)"));
		addShot(row, "rain", View.OFF);
		addShot(row, "rain", null);
		STEPS.add(() -> command("weather clear"));
	}

	/**
	 * At noon and low sun, from where the player stands but looking 10° up: the clouds out to the horizon, lit
	 * (Java's mesh near, the far clouds beyond it) against vanilla (Distant Horizons' own clouds, when it's
	 * installed, show how far its terrain reaches).
	 */
	private static void addHorizonShots() {
		Minecraft minecraft = Minecraft.getInstance();
		var player = minecraft.player;
		String back = String.format(java.util.Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f", player.getX(), player.getY(),
				player.getZ(), player.getYRot(), player.getXRot());
		String up = String.format(java.util.Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f", player.getX(), player.getY(),
				player.getZ(), player.getYRot(), HORIZON_PITCH);
		List<ContactSheet.Cell> row = new ArrayList<>();
		sheet.add(row);
		long[] times = {6000, 11500};
		String[] labels = {"horizon noon", "horizon low sun"};
		for (int i = 0; i < times.length; i++) {
			long time = times[i];
			String label = labels[i];
			STEPS.add(() -> {
				command("time set " + time);
				command(up);
				wait = TICKS_AFTER_TIME_CHANGE;
			});
			STEPS.add(() -> {
				AutoExposure.settle();
				wait = TICKS_AFTER_VIEW_CHANGE;
			});
			STEPS.add(() -> report.lightingAt(label + " (" + time + ")"));
			addShot(row, label, View.OFF);
			addShot(row, label, null);
		}
		STEPS.add(() -> command(back));
	}

	/** A null view means vanilla terrain (deferred lighting off). */
	private static void addShot(List<ContactSheet.Cell> row, String timeLabel, View view) {
		String viewLabel = view == null ? "vanilla" : view == View.OFF ? "lit" : view.label;
		String file = (timeLabel + "_" + viewLabel).replace(' ', '-') + ".png";
		row.add(new ContactSheet.Cell(folder.resolve(file), timeLabel + " - " + viewLabel));
		STEPS.add(() -> {
			DeferredRenderer.setEnabled(view != null);
			GBufferDebugView.setView(view == null ? View.OFF : view);
			wait = TICKS_AFTER_VIEW_CHANGE;
		});
		STEPS.add(() -> shoot(folder.resolve(file)));
	}

	private static void step() {
		if (wait > 0) {
			wait--;
			return;
		}
		Runnable next = STEPS.poll();
		if (next != null) {
			next.run();
		}
	}

	private static void shoot(Path file) {
		PENDING_SHOTS.incrementAndGet();
		Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), (NativeImage image) -> {
			try (image) {
				image.writeToFile(file);
			} catch (IOException e) {
				BedrockVisualsClient.LOGGER.warn("[capture] could not save {}", file, e);
			} finally {
				PENDING_SHOTS.decrementAndGet();
			}
		});
	}

	/** Pressing the key again mid-capture stops it and puts everything back; screenshots already taken stay. */
	private static void cancel() {
		STEPS.clear();
		wait = 0;
		restore();
		running = false;
		message("Capture cancelled.");
	}

	private static void restore() {
		Minecraft minecraft = Minecraft.getInstance();
		DeferredRenderer.setEnabled(lightingBefore);
		GBufferDebugView.setView(viewBefore);
		command("time set " + timeBefore);
		if (minecraft.gui.hud.isHidden() != hudWasHidden) {
			minecraft.gui.hud.toggle();
		}
	}

	/** Waits for the last screenshots to be written, then builds the sheet and report off the render thread. */
	private static void finishWhenSaved() {
		if (PENDING_SHOTS.get() > 0) {
			STEPS.addFirst(TestCapture::finishWhenSaved);
			wait = 1;
			return;
		}
		running = false;
		Minecraft minecraft = Minecraft.getInstance();
		report.logExcerpt(minecraft.gameDirectory.toPath());
		String text = report.text();
		minecraft.keyboardHandler.setClipboard(text);
		Path captureFolder = folder;
		List<List<ContactSheet.Cell>> cells = sheet;
		Util.ioPool().execute(() -> {
			try {
				Files.writeString(captureFolder.resolve("report.txt"), text, StandardCharsets.UTF_8);
				ContactSheet.write(cells, captureFolder.resolve("contact-sheet.png"));
				minecraft.execute(() -> {
					message("Capture saved. The report is on your clipboard; send it with contact-sheet.png.");
					Blaze3D.openPath(captureFolder);
				});
			} catch (IOException | RuntimeException e) {
				BedrockVisualsClient.LOGGER.error("[capture] could not write the contact sheet or report", e);
				minecraft.execute(() -> message("Capture saved, but the contact sheet failed; see the log."));
			}
		});
	}

	private static void command(String command) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.connection.sendCommand(command);
		}
	}

	private static void message(String text) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(Component.literal("[Bedrock Visuals] " + text));
		}
	}
}
