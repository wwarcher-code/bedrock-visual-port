package dev.bedrockvisuals.render;

import dev.bedrockvisuals.lod.DistantTerrain;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.devtools.FrameTimes;
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

	private static boolean solidEntitiesDrawn;
	private static boolean translucentEntitiesDrawn;
	private static boolean earlyEntitiesFailed;

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

	/**
	 * Called just before Minecraft opens its main render pass.
	 *
	 * @param solidEntities Minecraft's executeSolid for this frame's prepared entity draws (entity shadows, and
	 *            the entities drawn here before the water: drawEntitiesBeforeWater)
	 * @param translucentEntities its executeTranslucent, with classic transparency (null with improved
	 *            transparency, where they go through the OIT passes)
	 */
	public static void beforeMainPass(ChunkSectionsToRender chunks, RenderTarget main, GpuSampler sampler,
			TextureAtlas blockAtlas, Consumer<RenderPass> solidEntities, Consumer<RenderPass> translucentEntities) {
		FrameTimes.beginFrame();
		try {
			renderBeforeMainPass(chunks, main, sampler, blockAtlas, solidEntities, translucentEntities);
		} finally {
			FrameTimes.endMod();
		}
	}

	private static void renderBeforeMainPass(ChunkSectionsToRender chunks, RenderTarget main, GpuSampler sampler,
			TextureAtlas blockAtlas, Consumer<RenderPass> solidEntities, Consumer<RenderPass> translucentEntities) {
		solidEntitiesDrawn = false;
		translucentEntitiesDrawn = false;
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
		FrameTimes.section(FrameTimes.Metric.PASSES_CPU);
		Underwater.update();
		FrameTimes.section(FrameTimes.Metric.UNDERWATER);
		WaterColumns.update();
		FrameTimes.section(FrameTimes.Metric.WATER_COLUMNS);
		DistantHeights.update();
		FrameTimes.section(FrameTimes.Metric.DISTANT_HEIGHTS);
		WaterPass.updateExtinction();
		FrameTimes.section(FrameTimes.Metric.WATER_EXTINCTION);
		LightVolume.update();
		FrameTimes.section(FrameTimes.Metric.LIGHT_VOLUME);
		BlockLightColors.update();
		FrameTimes.section(FrameTimes.Metric.BLOCK_LIGHT_COLORS);
		PointLights.update();
		FrameTimes.section(FrameTimes.Metric.POINT_LIGHTS);
		// Placeholders and buffers the water and cloud passes bind are created here, outside any render pass
		// (uploads can't happen while one is open).
		CloudShadows.viewOrEmpty();
		LocalExposure.ensureData();
		WaterPass.prepare(blockAtlas, main, false);
		LightingState light = enabled && !lightingFailed ? LightingState.current() : null;
		FrameTimes.section(FrameTimes.Metric.PASSES_CPU);
		if (light != null) {
			SceneProbe.beginFrame();
			ShadowPass.render(chunks, sampler, blockAtlas.getTextureView(), light);
		}
		FrameTimes.section(FrameTimes.Metric.SHADOW_CPU);
		GBufferPass.render(chunks, main, sampler, blockAtlas.getTextureView(), MaterialAtlas.view(blockAtlas));
		FrameTimes.section(FrameTimes.Metric.GBUFFER_CPU);
		if (light != null && light.atmosphere() != null && GBufferPass.hasTerrain()) {
			// The reflection probe's scene, when a capture is due (SceneProbe).
			SceneProbe.capture(chunks, sampler, blockAtlas.getTextureView(), MaterialAtlas.view(blockAtlas),
					GBufferPass.projection());
		}
		FrameTimes.section(FrameTimes.Metric.SCENE_PROBE_CPU);
		if (light == null) {
			return;
		}
		// Entities into the shadow cascades too, before the lighting pass reads them.
		EntityShadows.render(solidEntities, GBufferPass.projection(), GBufferPass.modelView());
		FrameTimes.section(FrameTimes.Metric.ENTITY_SHADOWS_CPU);
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
			FrameTimes.section(FrameTimes.Metric.LIGHTING_CPU);
			RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(gbuffer.depth(),
					main.getDepthTexture(), 0, 0, 0, 0, 0, main.width, main.height);
			replacedTerrain = terrain;
			replacedSky = light.atmosphere() != null;
			framesLit++;
			drawEntitiesBeforeWater(main, solidEntities, translucentEntities);
			WaterPass.prepare(blockAtlas, main, true);
			// The water's screen-space reflections, accumulated over frames (ReflectionPass).
			ReflectionPass.render(gbuffer);
			if (!reported) {
				reported = true;
				BedrockVisualsClient.LOGGER.info("[lighting] first frame lit by the deferred lighting pass");
			}
		} catch (RuntimeException e) {
			lightingFailed = true;
			BedrockVisualsClient.LOGGER.error("[lighting] deferred lighting failed; vanilla terrain until restart", e);
		}
	}

	/**
	 * Draws the frame's entities (Minecraft's solid ones, and with classic transparency its translucent ones)
	 * into the lit frame now, before the water copies the scene beneath it (WaterPass.prepare): the water's
	 * shader composes the whole picture through its surface from that copy, so entities Minecraft drew after it
	 * (in its main pass, before the water) were painted over wherever they were under water. The main pass
	 * then skips them (LevelRendererMixin). They're drawn in Minecraft's order (solid, then translucent, both
	 * before the water) with the terrain's depth already in the frame.
	 */
	private static void drawEntitiesBeforeWater(RenderTarget main, Consumer<RenderPass> solidEntities,
			Consumer<RenderPass> translucentEntities) {
		if (earlyEntitiesFailed || solidEntities == null) {
			return;
		}
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals entities before the water")
				.withColorAttachment(main.getColorTextureView())
				.withDepthAttachment(main.getDepthTextureView(), OptionalDouble.empty())
				.build();
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
			RenderSystem.bindDefaultUniforms(pass);
			solidEntitiesDrawn = true;
			solidEntities.accept(pass);
			if (translucentEntities != null) {
				translucentEntitiesDrawn = true;
				translucentEntities.accept(pass);
			}
		} catch (RuntimeException e) {
			// Part of them may be drawn: the main pass draws them again this frame, and from the next on as before.
			earlyEntitiesFailed = true;
			solidEntitiesDrawn = false;
			translucentEntitiesDrawn = false;
			BedrockVisualsClient.LOGGER.error("[entities] drawing entities before the water failed; "
					+ "they're drawn in Minecraft's pass (and hidden under water) until restart", e);
		}
	}

	/** Whether this frame's solid entities were drawn before the water (the main pass skips them). */
	public static boolean solidEntitiesDrawn() {
		return solidEntitiesDrawn;
	}

	/** Whether this frame's translucent entities were drawn before the water (the main pass skips them). */
	public static boolean translucentEntitiesDrawn() {
		return translucentEntitiesDrawn;
	}

	/** After the main pass: the next frame decides again. */
	public static void afterMainPass() {
		FrameTimes.afterMain();
		solidEntitiesDrawn = false;
		translucentEntitiesDrawn = false;
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
