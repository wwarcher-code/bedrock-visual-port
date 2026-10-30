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
	private enum View {
		OFF("off", null),
		ALBEDO("albedo", GBuffer.Target.ALBEDO),
		NORMAL("normal", GBuffer.Target.NORMAL),
		MATERIAL("material", GBuffer.Target.MATERIAL),
		LIGHT("light", GBuffer.Target.LIGHT),
		SHADOWS("shadows", null);

		final String label;
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

	/** True when the lighting pass should draw its shadow test instead of lit color. */
	public static boolean showsShadows() {
		return showing == View.SHADOWS;
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
