package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import dev.bedrockvisuals.bedrock.AtmosphereSettings;
import dev.bedrockvisuals.bedrock.Curve;
import dev.bedrockvisuals.bedrock.FogSettings;
import dev.bedrockvisuals.bedrock.LightingSettings;
import dev.bedrockvisuals.bedrock.Rgba;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * This frame's inputs to Bedrock's sky: its atmosphere settings and its sun and moon, as the engine hands them
 * to the sky radiance function (docs/bedrock-renderer-notes.md, "Atmosphere"). Vibrant Visuals draws every sky
 * pixel with that function (it's the distance fog at infinity), so this replaces Java's sky gradient and
 * sunset fan; Java's sun, moon and stars are still drawn on top. Values are in Bedrock's units: the sun's
 * illuminance is 100 at noon, and the shader converts to this renderer's units with
 * {@link AutoExposure#UNIT_SCALE}.
 *
 * @param zenith, horizon the sky colours, linear RGB
 * @param blendStops horizon blend stops: min, start, mie start, max
 * @param scattering Rayleigh strength, sun Mie strength, moon Mie strength, sun glare shape
 * @param sun, moon linear colour and illuminance
 * @param fog distance fog into the sky (Bedrock's {@code FogAndDistanceControl}): start and 1 / (end - start)
 *            as fractions of the fog distance, the fog distance in blocks, and 1 when on
 */
public record SkyAtmosphere(Vector3f zenith, Vector3f horizon, Vector4f blendStops, Vector4f scattering,
		Vector4f sun, Vector4f moon, Vector4f fog) {
	/** std140 size of the AtmosphereData block (lighting/sky.glsl): seven vec4s. */
	static final int DATA_SIZE = 7 * 16;

	/**
	 * TUNE: the sun's (and moon's) strength in the sky fades in over these elevations, in degrees. Bedrock's
	 * engine fades it by the light's elevation between two angles from its renderer settings, which weren't
	 * read; these fit the reference shots (about 0.86 at the low-sun shot's 7°).
	 */
	static final float FADE_START_DEGREES = -10f;
	static final float FADE_END_DEGREES = 10f;

	/**
	 * The sky doesn't follow the lighting's illuminance curve, which falls to a quarter by the low-sun shot:
	 * Bedrock's afternoon sky is as bright as noon's. It takes the light's full strength (the curve's peak)
	 * faded by elevation, which matches the reference shots at noon, afternoon and low sun.
	 */
	static SkyAtmosphere sample(AtmosphereSettings atmosphere, LightingSettings lighting, float time,
			Vector3f sunDirection, Vector3f moonDirection, Vector4f fog) {
		Rgba sunColor = lighting.sun().color().sample(time);
		Rgba moonColor = lighting.moon().color().sample(time);
		return new SkyAtmosphere(
				linear(atmosphere.skyZenithColor().sample(time)),
				linear(atmosphere.skyHorizonColor().sample(time)),
				new Vector4f(atmosphere.horizonBlendMin().sample(time), atmosphere.horizonBlendStart().sample(time),
						atmosphere.horizonBlendMieStart().sample(time), atmosphere.horizonBlendMax().sample(time)),
				new Vector4f(atmosphere.rayleighStrength().sample(time), atmosphere.sunMieStrength().sample(time),
						atmosphere.moonMieStrength().sample(time), atmosphere.sunGlareShape().sample(time)),
				new Vector4f(linear(sunColor), Curve.max(lighting.sun().illuminance()) * fade(sunDirection)),
				new Vector4f(linear(moonColor), Curve.max(lighting.moon().illuminance()) * fade(moonDirection)),
				fog);
	}

	/**
	 * Bedrock's air distance fog: terrain fades into the sky (the same atmosphere function, so its colour is
	 * the sky's behind it) from {@code fog_start} to {@code fog_end}, fractions of the render distance (or
	 * blocks, for "fixed" fogs). Off ({@code w} 0) when there's no air fog.
	 */
	static Vector4f distanceFog(FogSettings.DistanceFog air, float renderDistanceBlocks) {
		if (air == null || air.end() <= air.start()) {
			return new Vector4f(0);
		}
		float distance = air.relativeToRenderDistance() ? renderDistanceBlocks : 1f;
		return new Vector4f(air.start(), 1f / (air.end() - air.start()), distance, 1);
	}

	/** 0 below {@link #FADE_START_DEGREES}, 1 above {@link #FADE_END_DEGREES}, linear in the sine between. */
	static float fade(Vector3f towardsLight) {
		float start = (float) Math.sin(Math.toRadians(FADE_START_DEGREES));
		float end = (float) Math.sin(Math.toRadians(FADE_END_DEGREES));
		float elevation = towardsLight.y / Math.max(towardsLight.length(), 1e-6f);
		return Math.clamp((elevation - start) / (end - start), 0f, 1f);
	}

	/** Writes AtmosphereData; {@code null} writes a disabled block (the shader leaves Java's sky). */
	static void write(Std140Builder builder, SkyAtmosphere sky) {
		if (sky == null) {
			for (int i = 0; i < 7; i++) {
				builder.putVec4(0, 0, 0, 0);
			}
			return;
		}
		builder.putVec4(sky.zenith.x, sky.zenith.y, sky.zenith.z, 1);
		builder.putVec4(sky.horizon.x, sky.horizon.y, sky.horizon.z, 1f / AutoExposure.UNIT_SCALE);
		builder.putVec4(sky.blendStops);
		builder.putVec4(sky.scattering);
		builder.putVec4(sky.sun);
		builder.putVec4(sky.moon);
		builder.putVec4(sky.fog);
	}

	private static Vector3f linear(Rgba color) {
		return new Vector3f(toLinear(color.r()), toLinear(color.g()), toLinear(color.b()));
	}

	private static float toLinear(float srgb) {
		return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4f);
	}
}
