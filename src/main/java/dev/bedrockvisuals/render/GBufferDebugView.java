package dev.bedrockvisuals.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * F7 cycles the screen through debug views and back to the normal view: each G-buffer target (copied over the
 * world after Minecraft's main pass, so the HUD still draws on top), then the shadow test as the lighting pass
 * sees it (white lit, black shadowed, red outside the shadow map; needs deferred lighting on).
 */
public final class GBufferDebugView {
	public enum View {
		OFF("off", null),
		ALBEDO("albedo", GBuffer.Target.ALBEDO),
		NORMAL("normal", GBuffer.Target.NORMAL),
		MATERIAL("material", GBuffer.Target.MATERIAL),
		LIGHT("light", GBuffer.Target.LIGHT),
		SHADOWS("shadows", null),
		CASCADES("cascades", null),
		LOCAL_EXPOSURE("local exposure", null),
		BLOOM("bloom", null),
		WATER("water", null),
		WATER_HAZE("water haze", null),
		WATER_BENEATH("under water, no haze", null),
		WATER_SURFACE("water surface", null);

		public final String label;
		final GBuffer.Target target;

		View(String label, GBuffer.Target target) {
			this.label = label;
			this.target = target;
		}
	}

	private static final KeyMapping CYCLE = new KeyMapping("key.bedrockvisuals.gbuffer_debug", InputConstants.KEY_F7,
			KeyMapping.Category.MISC);

	private static View showing = View.OFF;

	private GBufferDebugView() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(CYCLE);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (CYCLE.consumeClick()) {
				showing = View.values()[(showing.ordinal() + 1) % View.values().length];
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.literal("Debug view: " + showing.label));
				}
			}
		});
	}

	public static View view() {
		return showing;
	}

	public static void setView(View view) {
		showing = view;
	}

	/** True when the lighting pass should draw its shadow test instead of lit color. */
	public static boolean showsShadows() {
		return showing == View.SHADOWS || showing == View.CASCADES || showing == View.WATER;
	}

	/** DebugParams.x for the lighting pass: 0 lit, 1 the shadow test, 2 the shadow test tinted by cascade. */
	public static int shadowDebugMode() {
		return showing == View.WATER ? 3 : showing == View.CASCADES ? 2 : showing == View.SHADOWS ? 1 : 0;
	}

	/**
	 * True when lit pixels should show local exposure at work: drawn mid-grey, then multiplied per channel by
	 * red 2 where the pass applies, green its factor (above mid is lifted, below lowered), blue 2 where its
	 * grid has data. Plain grey means the pass's output never reached the image.
	 */
	public static boolean showsLocalExposure() {
		return showing == View.LOCAL_EXPOSURE;
	}

	/**
	 * DebugParams.w for the lighting data: 1 shows local exposure's factor, 2 only the bloom; 3 to 5 split the
	 * water seen from above into its parts: 3 only the scattered light (the fog grid's), 4 only what lies
	 * beneath with the water's dimming, 5 only the surface's reflection and glint.
	 */
	public static int postDebugMode() {
		return showing == View.LOCAL_EXPOSURE ? 1 : showing == View.BLOOM ? 2 : showing == View.WATER_HAZE ? 3
				: showing == View.WATER_BENEATH ? 4 : showing == View.WATER_SURFACE ? 5 : 0;
	}

	/** Called after Minecraft's main pass. */
	public static void apply(RenderTarget main) {
		GBuffer gbuffer = GBufferPass.gbuffer();
		if (showing.target == null || gbuffer.texture(showing.target) == null
				|| gbuffer.width() != main.width || gbuffer.height() != main.height) {
			return;
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(gbuffer.texture(showing.target),
				main.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
	}
}
