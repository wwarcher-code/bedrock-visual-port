package dev.bedrockvisuals.devtools;

import com.mojang.blaze3d.Blaze3D;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.render.DeferredRenderer;
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
 * normal and light once). It then restores the time and views, stitches a labelled contact sheet, writes a
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
	/** Views captured once, at the first time. */
	private static final View[] EXTRA_VIEWS = {View.ALBEDO, View.NORMAL, View.LIGHT};
	private static final int TICKS_AFTER_TIME_CHANGE = 15;
	private static final int TICKS_AFTER_VIEW_CHANGE = 3;

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
			}
		}
		STEPS.add(TestCapture::restore);
		STEPS.add(TestCapture::finishWhenSaved);
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
