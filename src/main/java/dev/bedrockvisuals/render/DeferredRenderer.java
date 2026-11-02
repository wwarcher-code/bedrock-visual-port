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
	/**
	 * True when the last frame's lighting pass drew Bedrock's sky. Java's sky pass runs before the lighting
	 * pass, so it follows the last frame: if the lighting stops, Java's full sky is back a frame later.
	 */
	private static boolean replacedSky;
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
		replacedSky = false;
		WaterPass.prepare(blockAtlas, main, false);
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
			replacedSky = light.atmosphere() != null;
			WaterPass.prepare(blockAtlas, main, true);
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

	/**
	 * Whether Java's sky pass should leave out its sky gradient, sunset fan and dark disc (and clear to black),
	 * keeping only the sun, moon and stars for the lighting pass to add Bedrock's sky onto.
	 */
	public static boolean replacesSky() {
		return replacedSky && enabled && !lightingFailed;
	}

	/** Whether Minecraft's main pass should skip drawing Sodium's opaque terrain this frame. */
	public static boolean replacesTerrain() {
		return replacedTerrain;
	}
}
