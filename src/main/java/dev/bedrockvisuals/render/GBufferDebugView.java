package dev.bedrockvisuals.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * F7 cycles the screen through the G-buffer's targets and back to the normal view. The chosen target is
 * copied over the world after Minecraft's main pass, so the HUD still draws on top.
 */
public final class GBufferDebugView {
	private static final KeyMapping CYCLE = new KeyMapping("key.bedrockvisuals.gbuffer_debug", InputConstants.KEY_F7,
			KeyMapping.Category.MISC);

	/** Null when the normal view is showing. */
	private static GBuffer.Target showing;

	private GBufferDebugView() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(CYCLE);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (CYCLE.consumeClick()) {
				showing = next(showing);
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.literal(
							"G-buffer view: " + (showing == null ? "off" : showing.label)));
				}
			}
		});
	}

	private static GBuffer.Target next(GBuffer.Target current) {
		GBuffer.Target[] targets = GBuffer.Target.values();
		if (current == null) {
			return targets[0];
		}
		return current.ordinal() + 1 < targets.length ? targets[current.ordinal() + 1] : null;
	}

	/** Called after Minecraft's main pass. */
	public static void apply(RenderTarget main) {
		GBuffer gbuffer = GBufferPass.gbuffer();
		if (showing == null || gbuffer.texture(showing) == null
				|| gbuffer.width() != main.width || gbuffer.height() != main.height) {
			return;
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(gbuffer.texture(showing),
				main.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
	}
}
