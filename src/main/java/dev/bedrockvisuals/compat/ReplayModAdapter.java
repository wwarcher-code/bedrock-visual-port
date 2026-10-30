package dev.bedrockvisuals.compat;

import dev.bedrockvisuals.BedrockVisualsClient;

/**
 * The only class that touches Replay Mod internals. The renderer switches to the offline preset
 * while {@link #isExporting()} is true.
 *
 * <p>Spike 1c: confirms an export starts and finishes under Vulkan. Replay Mod reads frames back
 * through OpenGL pixel buffers, so expect the capture itself to be the part that fails.
 */
public final class ReplayModAdapter {
	private static volatile boolean exporting;

	private ReplayModAdapter() {
	}

	public static boolean isExporting() {
		return exporting;
	}

	public static void onExportStart(int totalFrames) {
		exporting = true;
		BedrockVisualsClient.LOGGER.info("[spike 1c] Replay Mod export started, {} frames", totalFrames);
	}

	public static void onExportEnd(int framesDone) {
		exporting = false;
		BedrockVisualsClient.LOGGER.info("[spike 1c] Replay Mod export finished after {} frames", framesDone);
	}
}
