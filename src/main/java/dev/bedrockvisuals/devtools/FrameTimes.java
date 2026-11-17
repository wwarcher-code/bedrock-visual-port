package dev.bedrockvisuals.devtools;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Logs frame times for a while, for performance work: the time between frames on the CPU, the mod's CPU work
 * before Minecraft's main pass (each per-frame update on its own, then the passes it records), and on the GPU
 * the time between frames, the mod's passes (from the start of beforeMainPass to its end) and Minecraft's main
 * pass. Started with the script line {@code frametimes <seconds> <file>} (ScriptRunner); when the time is up it
 * writes each frame's times to the file as CSV and a summary (average, 99th percentile, the mean of the worst 1%
 * and the maximum of each) to the file with {@code .summary.txt} appended, and logs the summary.
 *
 * <p>The GPU times come from timestamp queries written into the frame's commands, read back a few frames later.
 * Their unit is the device's timestamp period, which the API doesn't expose: it's found by matching the GPU's
 * average frame interval to the CPU's over the run (they're equal over any long run), so the GPU columns are in
 * milliseconds like the rest.
 *
 * <p>Costs nothing while not recording beyond a field test per section.
 */
public final class FrameTimes {
	/** What's timed each frame, in the CSV's column order. */
	public enum Metric {
		FRAME("frame_ms"),
		MOD_CPU("mod_cpu_ms"),
		UNDERWATER("underwater_ms"),
		WATER_COLUMNS("water_columns_ms"),
		WATER_REMAP("water_remap_ms"),
		DISTANT_HEIGHTS("distant_heights_ms"),
		WATER_EXTINCTION("water_extinction_ms"),
		LIGHT_VOLUME("light_volume_ms"),
		BLOCK_LIGHT_COLORS("block_light_colors_ms"),
		POINT_LIGHTS("point_lights_ms"),
		PASSES_CPU("passes_cpu_ms"),
		SHADOW_CPU("shadow_cpu_ms"),
		GBUFFER_CPU("gbuffer_cpu_ms"),
		SCENE_PROBE_CPU("scene_probe_cpu_ms"),
		ENTITY_SHADOWS_CPU("entity_shadows_cpu_ms"),
		LIGHTING_CPU("lighting_cpu_ms"),
		TICK("client_tick_ms"),
		BAND_COLUMNS("band_columns"),
		REMAPPED_CHUNKS("remapped_chunks"),
		DIRTY_MARKS("dirty_marks"),
		FOLLOWED_CHUNKS("followed_chunks"),
		GPU_FRAME("gpu_frame_ms"),
		GPU_MOD("gpu_mod_ms"),
		GPU_MAIN("gpu_main_ms"),
		HEAP("heap_mb");

		final String column;

		Metric(String column) {
			this.column = column;
		}
	}

	private static final Metric[] METRICS = Metric.values();
	private static final int MAX_FRAMES = 1 << 17;
	/** Frames of timestamps in flight; a frame's are read back before its slots are written again. */
	private static final int RING = 16;
	private static final int STAMPS = 3;

	private static boolean recording;
	private static long endNanos;
	private static Path out;
	private static double[][] values;
	private static int frame;
	private static long lastFrameStart;
	private static long sectionStart;
	private static long modStart;
	/** Raw GPU timestamps per recorded frame: beforeMainPass start, its end, the main pass's end. */
	private static long[][] stamps;
	private static GpuQueryPool pool;
	private static boolean gpuFailed;
	private static int gpuDropped;

	private FrameTimes() {
	}

	public static boolean recording() {
		return recording;
	}

	/** Starts recording for the given number of seconds; the results go to the file when it ends. */
	public static void start(double seconds, Path file) {
		values = new double[METRICS.length][MAX_FRAMES];
		for (double[] column : values) {
			Arrays.fill(column, Double.NaN);
		}
		stamps = new long[STAMPS][MAX_FRAMES];
		frame = -1;
		lastFrameStart = 0;
		gpuDropped = 0;
		out = file;
		endNanos = System.nanoTime() + (long) (seconds * 1e9);
		recording = true;
		BedrockVisualsClient.LOGGER.info("[frametimes] recording {} s to {}", seconds, file);
	}

	/** At the start of DeferredRenderer.beforeMainPass. */
	public static void beginFrame() {
		if (!recording) {
			return;
		}
		long now = System.nanoTime();
		if (now >= endNanos) {
			finish();
			return;
		}
		if (frame + 1 >= MAX_FRAMES) {
			finish();
			return;
		}
		if (frame >= 0 && lastFrameStart != 0) {
			values[Metric.FRAME.ordinal()][frame] = (now - lastFrameStart) / 1e6;
		}
		frame++;
		lastFrameStart = now;
		modStart = now;
		sectionStart = now;
		Runtime runtime = Runtime.getRuntime();
		values[Metric.HEAP.ordinal()][frame] = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0);
		values[Metric.TICK.ordinal()][frame] = pendingTick;
		pendingTick = 0;
		readBack(frame - RING);
		stamp(0);
	}

	/** Ends the section that began at the last call (or the frame's start), crediting it to the metric. */
	public static void section(Metric metric) {
		if (!recording || frame < 0) {
			return;
		}
		long now = System.nanoTime();
		add(metric, (now - sectionStart) / 1e6);
		sectionStart = now;
	}

	/** Adds a count (not a time) to this frame's metric, e.g. columns encoded. */
	public static void count(Metric metric, double n) {
		if (!recording || frame < 0) {
			return;
		}
		add(metric, n);
	}

	private static long tickStart;
	private static double pendingTick;

	/** Client tick start and end (they run before the frame's render, so they count towards the next frame). */
	public static void tickStart() {
		if (recording) {
			tickStart = System.nanoTime();
		}
	}

	public static void tickEnd() {
		if (recording && tickStart != 0) {
			pendingTick += (System.nanoTime() - tickStart) / 1e6;
			tickStart = 0;
		}
	}

	private static void add(Metric metric, double ms) {
		double v = values[metric.ordinal()][frame];
		values[metric.ordinal()][frame] = Double.isNaN(v) ? ms : v + ms;
	}

	/** At the end of beforeMainPass (every return). */
	public static void endMod() {
		if (!recording || frame < 0) {
			return;
		}
		long now = System.nanoTime();
		add(Metric.PASSES_CPU, (now - sectionStart) / 1e6);
		values[Metric.MOD_CPU.ordinal()][frame] = (now - modStart) / 1e6;
		stamp(1);
	}

	/** After Minecraft's main pass. */
	public static void afterMain() {
		if (!recording || frame < 0) {
			return;
		}
		stamp(2);
	}

	private static void stamp(int which) {
		if (gpuFailed) {
			return;
		}
		try {
			if (pool == null) {
				pool = RenderSystem.getDevice().createTimestampQueryPool(RING * STAMPS);
			}
			RenderSystem.getDevice().createCommandEncoder().writeTimestamp(pool, (frame % RING) * STAMPS + which);
			stamps[which][frame] = -1; // written, not read yet
		} catch (RuntimeException e) {
			gpuFailed = true;
			BedrockVisualsClient.LOGGER.warn("[frametimes] GPU timestamps unavailable; CPU times only", e);
		}
	}

	/** Reads a frame's timestamps back (before its slots are written again). */
	private static void readBack(int f) {
		if (f < 0 || pool == null || gpuFailed) {
			return;
		}
		try {
			OptionalLong[] got = pool.getValues((f % RING) * STAMPS, STAMPS);
			boolean dropped = false;
			for (int i = 0; i < STAMPS; i++) {
				if (stamps[i][f] == -1) {
					if (got[i].isPresent()) {
						stamps[i][f] = got[i].getAsLong();
					} else {
						stamps[i][f] = 0;
						dropped = true;
					}
				}
			}
			if (dropped) {
				gpuDropped++;
			}
		} catch (RuntimeException e) {
			gpuFailed = true;
			BedrockVisualsClient.LOGGER.warn("[frametimes] reading GPU timestamps failed; CPU times only", e);
		}
	}

	private static void finish() {
		recording = false;
		int frames = frame + 1;
		// The last frames' timestamps: wait for the GPU, then read what's there.
		for (int f = Math.max(0, frames - RING + 1); f < frames; f++) {
			readBack(f);
		}
		double period = gpuPeriod(frames);
		for (int f = 0; f < frames; f++) {
			long a = stamps[0][f];
			long b = stamps[1][f];
			long c = stamps[2][f];
			if (f + 1 < frames && a > 0 && stamps[0][f + 1] > 0) {
				values[Metric.GPU_FRAME.ordinal()][f] = (stamps[0][f + 1] - a) * period / 1e6;
			}
			if (a > 0 && b > 0) {
				values[Metric.GPU_MOD.ordinal()][f] = (b - a) * period / 1e6;
			}
			if (b > 0 && c > 0) {
				values[Metric.GPU_MAIN.ordinal()][f] = (c - b) * period / 1e6;
			}
		}
		StringBuilder csv = new StringBuilder();
		csv.append("frame");
		for (Metric metric : METRICS) {
			csv.append(',').append(metric.column);
		}
		csv.append('\n');
		for (int f = 0; f < frames; f++) {
			csv.append(f);
			for (Metric metric : METRICS) {
				double v = values[metric.ordinal()][f];
				csv.append(',');
				if (!Double.isNaN(v)) {
					csv.append(String.format(Locale.ROOT, "%.3f", v));
				}
			}
			csv.append('\n');
		}
		StringBuilder summary = new StringBuilder();
		summary.append(String.format(Locale.ROOT, "%d frames; GPU timestamp period %.3f ns%s%s%n", frames, period,
				gpuFailed ? " (GPU timestamps failed)" : "",
				gpuDropped > 0 ? ", " + gpuDropped + " frames' GPU times not ready in time" : ""));
		summary.append(String.format(Locale.ROOT, "%-22s %9s %9s %9s %9s%n", "metric", "avg", "p99", "worst1%",
				"max"));
		for (Metric metric : METRICS) {
			double[] sorted = Arrays.stream(values[metric.ordinal()], 0, Math.max(frames, 0))
					.filter(v -> !Double.isNaN(v)).sorted().toArray();
			if (sorted.length == 0) {
				continue;
			}
			double sum = 0;
			for (double v : sorted) {
				sum += v;
			}
			int worstCount = Math.max(1, sorted.length / 100);
			double worst = 0;
			for (int i = sorted.length - worstCount; i < sorted.length; i++) {
				worst += sorted[i];
			}
			double p99 = sorted[Math.min(sorted.length - 1, (int) Math.floor(sorted.length * 0.99))];
			summary.append(String.format(Locale.ROOT, "%-22s %9.3f %9.3f %9.3f %9.3f%n", metric.column,
					sum / sorted.length, p99, worst / worstCount, sorted[sorted.length - 1]));
		}
		try {
			Files.writeString(out, csv, StandardCharsets.UTF_8);
			Files.writeString(Path.of(out + ".summary.txt"), summary, StandardCharsets.UTF_8);
		} catch (IOException e) {
			BedrockVisualsClient.LOGGER.error("[frametimes] could not write {}", out, e);
		}
		BedrockVisualsClient.LOGGER.info("[frametimes] wrote {}\n{}", out, summary);
		values = null;
		stamps = null;
	}

	/**
	 * Nanoseconds per GPU timestamp tick: the CPU's total frame time over the GPU's, across frames with both
	 * (the GPU can't fall behind or run ahead of the CPU over a run). Rounded to 1 when within 5% of it, the
	 * period of most desktop GPUs, where the difference is only the run's ends.
	 */
	private static double gpuPeriod(int frames) {
		double cpu = 0;
		double gpu = 0;
		for (int f = 0; f + 1 < frames; f++) {
			double interval = values[Metric.FRAME.ordinal()][f];
			if (!Double.isNaN(interval) && stamps[0][f] > 0 && stamps[0][f + 1] > 0) {
				cpu += interval * 1e6;
				gpu += stamps[0][f + 1] - stamps[0][f];
			}
		}
		if (gpu <= 0) {
			return 1;
		}
		double period = cpu / gpu;
		return Math.abs(period - 1) < 0.05 ? 1 : period;
	}
}
