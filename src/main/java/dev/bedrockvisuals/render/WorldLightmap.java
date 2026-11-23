package dev.bedrockvisuals.render;

import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;

/**
 * Minecraft's lightmap at full sky light, for each block light level, as its lightmap shader (core/lightmap.fsh)
 * computes it this frame. Distant Horizons multiplies its terrain colour by the lightmap; the lighting pass
 * divides this back out (at the block light DH had there, DistantHeights' light map), so DH's colour can stand
 * in for albedo at any time of day and beside any light.
 */
public final class WorldLightmap {
	private static final Vector3f[] atBlockLevel = new Vector3f[16];

	static {
		for (int i = 0; i < 16; i++) {
			atBlockLevel[i] = new Vector3f(1);
		}
	}

	private WorldLightmap() {
	}

	/** Called with each frame's lightmap state (LightmapMixin). */
	public static void capture(LightmapRenderState state) {
		for (int level = 0; level < 16; level++) {
			atBlockLevel[level].set(compute(state, level / 15f));
		}
	}

	/** core/lightmap.fsh at sky level 15 (get_brightness(1) = 1) and the given block level (0 to 1). */
	private static Vector3f compute(LightmapRenderState state, float blockLevel) {
		Vector3f color = new Vector3f(state.ambientColor);
		if (state.nightVisionColor != null) {
			Vector3f nightVision = new Vector3f(state.nightVisionColor).mul(state.nightVisionEffectIntensity);
			color.max(nightVision);
		}
		color.add(new Vector3f(state.skyLightColor).mul(state.skyFactor));
		if (blockLevel > 0 && state.blockLightTint != null) {
			float brightness = blockLevel / (4 - 3 * blockLevel) * state.blockFactor;
			float parabolic = (2 * blockLevel - 1) * (2 * blockLevel - 1);
			Vector3f tint = new Vector3f(state.blockLightTint).lerp(new Vector3f(1), 0.9f * parabolic);
			color.add(tint.mul(brightness));
		}
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
		return color;
	}

	private static float clamp(float v) {
		return Math.max(0, Math.min(1, v));
	}

	/** The lightmap's colour at full sky light and no block light, this frame (white before the first). */
	static Vector3f fullSky() {
		return atBlockLevel[0];
	}

	/** The lightmap's colour at full sky light and the given block light level (0 to 15), this frame. */
	static Vector3f atBlockLevel(int level) {
		return atBlockLevel[Math.max(0, Math.min(15, level))];
	}
}
