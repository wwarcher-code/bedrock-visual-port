package dev.bedrockvisuals.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.bedrockvisuals.BedrockVisualsClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.network.chat.Component;

/**
 * Runs the deferred passes before Minecraft's main pass: terrain into the sun's shadow map and the G-buffer,
 * the G-buffer lit into the main target, then the G-buffer's depth copied over the main depth so entities and
 * translucent terrain still sort against the terrain. F8 switches the lighting off to compare with vanilla.
 */
public final class DeferredRenderer {
	private static final KeyMapping TOGGLE = new KeyMapping("key.bedrockvisuals.toggle_lighting",
			InputConstants.KEY_F8, KeyMapping.Category.MISC);

	private static boolean enabled = true;
	/** True when this frame's terrain was lit by the lighting pass, so the main pass must skip it. */
	private static boolean replacedTerrain;
	private static boolean lightingFailed;
	private static boolean reported;

	private DeferredRenderer() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(TOGGLE);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE.consumeClick()) {
				enabled = !enabled;
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.literal("Deferred lighting: " + (enabled ? "on" : "off")));
				}
			}
		});
	}

	/** Called just before Minecraft opens its main render pass. */
	public static void beforeMainPass(ChunkSectionsToRender chunks, RenderTarget main, GpuSampler sampler,
			TextureAtlas blockAtlas) {
		replacedTerrain = false;
		WaterPass.prepare(blockAtlas, false);
		LightingState light = enabled && !lightingFailed ? LightingState.current() : null;
		if (light != null) {
			ShadowPass.render(chunks, sampler, blockAtlas.getTextureView(), light);
		}
		GBufferPass.render(chunks, main, sampler, blockAtlas.getTextureView(), MaterialAtlas.view(blockAtlas));
		if (light == null || !GBufferPass.hasTerrain()) {
			return;
		}
		GBuffer gbuffer = GBufferPass.gbuffer();
		try {
			LightingPass.render(main, gbuffer, GBufferPass.projection(), GBufferPass.modelView(), light);
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(gbuffer.depth(),
					main.getDepthTexture(), 0, 0, 0, 0, 0, main.width, main.height);
			replacedTerrain = true;
			WaterPass.prepare(blockAtlas, true);
			if (!reported) {
				reported = true;
				BedrockVisualsClient.LOGGER.info("[lighting] first frame lit by the deferred lighting pass");
			}
		} catch (RuntimeException e) {
			lightingFailed = true;
			BedrockVisualsClient.LOGGER.error("[lighting] deferred lighting failed; vanilla terrain until restart", e);
		}
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	public static boolean lightingFailed() {
		return lightingFailed;
	}

	/** Whether Minecraft's main pass should skip drawing Sodium's opaque terrain this frame. */
	public static boolean replacesTerrain() {
		return replacedTerrain;
	}
}
