package dev.bedrockvisuals.render;

import dev.bedrockvisuals.lod.DistantTerrain;
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
	private static long frames;
	private static long framesLit;
	private static long framesWithoutTerrain;
	private static long lastNote;
	private static String lastReason = "";
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
	/** @param solidEntities Minecraft's executeSolid for this frame's prepared entity draws (entity shadows) */
	public static void beforeMainPass(ChunkSectionsToRender chunks, RenderTarget main, GpuSampler sampler,
			TextureAtlas blockAtlas, java.util.function.Consumer<com.mojang.renderpearl.api.commands.RenderPass> solidEntities) {
		// Before the flags reset: whether the last frame drew Bedrock's sky decides who draws the clouds.
		CloudPass.updateDistantClouds();
		replacedTerrain = false;
		replacedSky = false;
		frames++;
		totalFrames++;
		// While this renderer lights Distant Horizons' terrain, DH's own composite (its lit colour and fog onto
		// the frame) is cancelled; with lighting off (F8) DH draws itself again. DH draws before this, so the
		// change takes effect from the next frame.
		boolean ownsDistant = enabled && !lightingFailed;
		DistantTerrain.source().ifPresent(source -> source.setRendererOwnsComposite(ownsDistant));
		ownsDistantComposite = ownsDistant;
		Underwater.update();
		WaterColumns.update();
		// Placeholders and buffers the water and cloud passes bind are created here, outside any render pass
		// (uploads can't happen while one is open).
		CloudShadows.viewOrEmpty();
		LocalExposure.ensureData();
		WaterPass.prepare(blockAtlas, main, false);
		LightingState light = enabled && !lightingFailed ? LightingState.current() : null;
		if (light != null) {
			ShadowPass.render(chunks, sampler, blockAtlas.getTextureView(), light);
		}
		GBufferPass.render(chunks, main, sampler, blockAtlas.getTextureView(), MaterialAtlas.view(blockAtlas));
		if (light == null) {
			return;
		}
		// Entities into the shadow cascades too, before the lighting pass reads them.
		EntityShadows.render(solidEntities, GBufferPass.projection(), GBufferPass.modelView());
		boolean terrain = GBufferPass.hasTerrain();
		// With no terrain this frame the G-buffer is still cleared (all sky), so the pass still draws the sky:
		// otherwise Java's sky would come back for a frame. Only a failed G-buffer stops it.
		if (GBufferPass.isDisabled() || (!terrain && light.atmosphere() == null)) {
			note(GBufferPass.isDisabled() ? "the G-buffer failed" : "no terrain in the G-buffer");
			return;
		}
		if (!terrain) {
			framesWithoutTerrain++;
			note("no terrain in the G-buffer (sky drawn alone)");
		}
		GBuffer gbuffer = GBufferPass.gbuffer();
		try {
			LightingPass.render(main, gbuffer, GBufferPass.projection(), GBufferPass.modelView(), light);
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(gbuffer.depth(),
					main.getDepthTexture(), 0, 0, 0, 0, 0, main.width, main.height);
			replacedTerrain = terrain;
			replacedSky = light.atmosphere() != null;
			framesLit++;
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

	/** Logs why a frame wasn't fully lit, at most once every few seconds so a run of them doesn't flood the log. */
	private static void note(String reason) {
		long now = System.nanoTime();
		if (now - lastNote > 5_000_000_000L || !reason.equals(lastReason)) {
			lastNote = now;
			lastReason = reason;
			BedrockVisualsClient.LOGGER.info("[lighting] frame {}: {}", frames, reason);
		}
	}

	/** Frame counts since the last call, for diagnostics; resets them. */
	public static String frameSummary() {
		String summary = framesLit + " of " + frames + " frames lit"
				+ (framesWithoutTerrain > 0 ? ", " + framesWithoutTerrain + " with no terrain in the G-buffer" : "");
		frames = 0;
		framesLit = 0;
		framesWithoutTerrain = 0;
		return summary;
	}

	private static boolean ownsDistantComposite;

	/** Whether this frame's Distant Horizons terrain is lit by the lighting pass rather than drawn by DH. */
	static boolean ownsDistantComposite() {
		return ownsDistantComposite;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	private static long totalFrames;

	/** Frames drawn since the game started (lit or not); the test capture waits on it. */
	public static long totalFrames() {
		return totalFrames;
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
