package dev.bedrockvisuals.render;

import com.mojang.blaze3d.pipeline.RenderTarget;

/** Calls from GameRendererMixin into package-private parts of the renderer. */
public final class RenderHooks {
	private RenderHooks() {
	}

	/** At the start of each draw of the level (each sub-frame of an offline frame). */
	public static void beginLevel() {
		RenderClock.beginLevel(OfflineRender.subframe());
	}

	/** After the level, before the hand and the HUD (after TAA's resolve). */
	public static void afterLevel(RenderTarget main) {
		OfflineRender.accumulate(main);
	}
}
