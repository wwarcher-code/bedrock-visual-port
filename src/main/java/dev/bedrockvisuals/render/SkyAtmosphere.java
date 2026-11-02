package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import dev.bedrockvisuals.bedrock.AtmosphereSettings;
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
 */
public record SkyAtmosphere(Vector3f zenith, Vector3f horizon, Vector4f blendStops, Vector4f scattering,
		Vector4f sun, Vector4f moon) {
	/** std140 size of the AtmosphereData block (lighting/sky.glsl): six vec4s. */
	static final int DATA_SIZE = 6 * 16;

	static SkyAtmosphere sample(AtmosphereSettings atmosphere, LightingSettings lighting, float time) {
		Rgba sunColor = lighting.sun().color().sample(time);
		Rgba moonColor = lighting.moon().color().sample(time);
		return new SkyAtmosphere(
				linear(atmosphere.skyZenithColor().sample(time)),
				linear(atmosphere.skyHorizonColor().sample(time)),
				new Vector4f(atmosphere.horizonBlendMin().sample(time), atmosphere.horizonBlendStart().sample(time),
						atmosphere.horizonBlendMieStart().sample(time), atmosphere.horizonBlendMax().sample(time)),
				new Vector4f(atmosphere.rayleighStrength().sample(time), atmosphere.sunMieStrength().sample(time),
						atmosphere.moonMieStrength().sample(time), atmosphere.sunGlareShape().sample(time)),
				new Vector4f(linear(sunColor), lighting.sun().illuminance().sample(time)),
				new Vector4f(linear(moonColor), lighting.moon().illuminance().sample(time)));
	}

	/** Writes AtmosphereData; {@code null} writes a disabled block (the shader leaves Java's sky). */
	static void write(Std140Builder builder, SkyAtmosphere sky) {
		if (sky == null) {
			for (int i = 0; i < 6; i++) {
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
	}

	private static Vector3f linear(Rgba color) {
		return new Vector3f(toLinear(color.r()), toLinear(color.g()), toLinear(color.b()));
	}

	private static float toLinear(float srgb) {
		return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4f);
	}
}
