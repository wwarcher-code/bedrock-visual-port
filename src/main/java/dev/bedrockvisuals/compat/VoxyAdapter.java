package dev.bedrockvisuals.compat;

import dev.bedrockvisuals.BedrockVisualsClient;
import me.cortex.voxy.client.core.AbstractRenderPipeline;

/**
 * The only class that touches Voxy internals.
 *
 * <p>Spike 1b: reports the first LOD pipeline run. Voxy 0.2.20 refuses the Vulkan backend and draws with
 * raw OpenGL, so this only fires when the game runs on OpenGL.
 */
public final class VoxyAdapter {
	private static volatile boolean reported;

	private VoxyAdapter() {
	}

	public static void onPipelineRun(AbstractRenderPipeline pipeline) {
		if (!reported) {
			reported = true;
			BedrockVisualsClient.LOGGER.info("[spike 1b] Voxy pipeline {} running", pipeline.getClass().getName());
		}
	}
}
