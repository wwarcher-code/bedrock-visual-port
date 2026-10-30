package dev.bedrockvisuals.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/**
 * The only class that touches Sodium internals. Mixins forward here, so a Sodium update breaks one file.
 *
 * <p>Spike 1a: reports, once per terrain pass, what Sodium draws into. The G-buffer redirect replaces
 * the pass's target here in step 2.
 */
public final class SodiumAdapter {
	private static final Set<TerrainRenderPass> REPORTED = ConcurrentHashMap.newKeySet();

	private SodiumAdapter() {
	}

	public static void onTerrainPass(TerrainRenderPass pass, RenderPass renderPass) {
		if (REPORTED.add(pass)) {
			BedrockVisualsClient.LOGGER.info("[spike 1a] Sodium terrain pass {} (translucent={}) target={} backend={}",
					pass.getPipeline(), pass.isTranslucent(), pass.getTarget(), RenderSystem.getBackendDescription());
		}
	}
}
