package dev.bedrockvisuals.render;

import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;

/**
 * Minecraft's lightmap at full sky light and no block light, as its lightmap shader (core/lightmap.fsh)
 * computes it this frame. Distant Horizons multiplies its terrain colour by the lightmap; the lighting pass
 * divides this back out, so DH's colour can stand in for albedo at any time of day.
 */
public final class WorldLightmap {
	private static final Vector3f fullSky = new Vector3f(1);

	private WorldLightmap() {
	}

	/** Called with each frame's lightmap state (LightmapMixin). */
	public static void capture(LightmapRenderState state) {
		// core/lightmap.fsh at sky level 15 (get_brightness(1) = 1) and block level 0 (no block light).
		Vector3f color = new Vector3f(state.ambientColor);
		if (state.nightVisionColor != null) {
			Vector3f nightVision = new Vector3f(state.nightVisionColor).mul(state.nightVisionEffectIntensity);
			color.max(nightVision);
		}
		color.add(new Vector3f(state.skyLightColor).mul(state.skyFactor));
		Vector3f darkened = new Vector3f(color).mul(0.7f, 0.6f, 0.6f);
		color.lerp(darkened, state.bossOverlayWorldDarkening);
		color.sub(state.darknessEffectScale, state.darknessEffectScale, state.darknessEffectScale);
		color.set(clamp(color.x), clamp(color.y), clamp(color.z));
		float max = Math.max(color.x, Math.max(color.y, color.z));
		if (max > 0) {
			float inverted = 1 - max;
			float scaled = 1 - inverted * inverted * inverted * inverted;
			Vector3f notGamma = new Vector3f(color).mul(scaled / max);
			color.lerp(notGamma, state.brightness);
		}
		fullSky.set(color);
	}

	private static float clamp(float v) {
		return Math.max(0, Math.min(1, v));
	}

	/** The lightmap's colour at full sky light, this frame (white before the first). */
	static Vector3f fullSky() {
		return fullSky;
	}
}
