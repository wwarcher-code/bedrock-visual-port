package dev.bedrockvisuals.render;

import dev.bedrockvisuals.bedrock.AtmosphereSettings;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.BedrockPack;
import dev.bedrockvisuals.bedrock.ClientBiome;
import dev.bedrockvisuals.bedrock.FogSettings;
import dev.bedrockvisuals.bedrock.LightingSettings;
import dev.bedrockvisuals.bedrock.Rgba;
import dev.bedrockvisuals.bedrock.TimeOfDay;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;
import dev.bedrockvisuals.lod.DistantTerrain;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * This frame's light sources, sampled from Bedrock's lighting and atmosphere settings for the player's biome
 * at the current time of day. Colors are linear RGB.
 *
 * <p>Bedrock's files give illuminance curves (the sun peaks at 100) but not how its renderer exposes them,
 * so the scale factors were fitted to Bedrock screenshots of the test scene (see below).
 */
public record LightingState(
		Vector3f sunDirection,
		Vector3f sun,
		Vector3f moonDirection,
		Vector3f moon,
		Vector3f sky,
		Vector3f skyHorizon,
		Vector3f ambient,
		Vector3f blockLight,
		SkyAtmosphere atmosphere) {

	/** TUNE: Bedrock's noon sun illuminance maps to 1. */
	private static final float SUN_SCALE = 1f / 100f;
	/*
	 * The factors below were fitted to Bedrock 1.26.60 itself: the test scene's grass (lit from above) and
	 * its pillar's camera-facing wall (sky, ground bounce and torch only), measured in Vibrant Visuals
	 * screenshots at noon, afternoon, low sun and midnight, against this renderer's model of the same pixels:
	 * sun, sky fill, bounce, ambient, moon, block light, Bedrock's histogram auto exposure (AutoExposure,
	 * metering the frame's sky and terrain), grading and its exact generic tone map. The fit lands within about
	 * 10 sRGB levels per channel. Bedrock lights far more from the sky and its ambient floor than from the sun,
	 * which is why its orange evening sun barely tints the ground, and walls in shade take the sky's blue.
	 * Light values here are before exposure.
	 */
	/** Bedrock's full moon (0.4) barely lights anything; the night is the ambient floor and the sky. */
	private static final float MOON_SCALE = 0.0001f;
	/** How strongly the sky's zenith color fills faces that see the sky. */
	private static final float SKY_FILL = 1.324f;
	/** Bedrock's ambient illuminance (0.02 in vanilla) as a floor everywhere. */
	private static final float AMBIENT_SCALE = 0.070f;
	/** Block light's strength, the same day and night: exposure makes torches glow at night and fade by day. */
	private static final float BLOCK_LIGHT_SCALE = 0.0593f;
	/**
	 * The sky's radiance for the exposure meter when Java's sky is drawn (no Bedrock atmosphere: outside the
	 * Overworld or without a pack), relative to its fill colours (deferred.fsh). With Bedrock's sky the meter
	 * reads that sky's own radiance. Calibrated on a
	 * real frame of the test scene at midnight (meter diagnostics: the brightest 20% was 20% sky at 0.42 and
	 * terrain at 0.55, settling at exposure 24 where the fit wants 18): 1.5x the fit's 0.0278 lifts the sky to
	 * about 0.63, which then fills the brightest 20% and settles near exposure 18.7. By day the exposure sits at
	 * Bedrock's clamp either way.
	 */
	static final float METER_SKY_SCALE = 0.0417f;
	/** Block light's strength; its colour by level is Bedrock's curve, in the shader (common.glsl). */
	private static final Vector3f BLOCK_LIGHT = new Vector3f(1.0f, 1.0f, 1.0f);

	public static LightingState current() {
		ClientLevel level = Minecraft.getInstance().level;
		long dayTime = level == null ? 6000 : level.getDefaultClockTime();
		float time = TimeOfDay.fromJavaDayTime(dayTime);
		Optional<BedrockPack> pack = BedrockInputs.pack();
		if (pack.isEmpty() || level == null || Minecraft.getInstance().player == null) {
			return fallback(time);
		}

		ClientBiome biome = pack.get().biome(level.getBiome(Minecraft.getInstance().player.blockPosition())
				.unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:plains"));
		LightingSettings lighting = pack.get().lighting(biome.lighting());
		AtmosphereSettings atmosphere = pack.get().atmosphere(biome.atmosphere());
		Rgba zenith = atmosphere.skyZenithColor().sample(time);
		Rgba horizon = atmosphere.skyHorizonColor().sample(time);

		Vector3f sunDirection = orbit(time, lighting.orbitalOffsetDegrees().sample(time));
		float sunLevel = lighting.sun().illuminance().sample(time) * SUN_SCALE;
		float moonLevel = lighting.moon().illuminance().sample(time) * MOON_SCALE;
		float skyIntensity = lighting.skyIntensity().sample(time);
		return new LightingState(
				sunDirection,
				linear(lighting.sun().color().sample(time)).mul(sunLevel),
				new Vector3f(sunDirection).negate(),
				linear(lighting.moon().color().sample(time)).mul(moonLevel),
				// The sky color curves already darken through the evening.
				linear(zenith).mul(SKY_FILL * skyIntensity),
				linear(horizon).mul(SKY_FILL * skyIntensity),
				linear(lighting.ambientColor()).mul(lighting.ambientIlluminance() * AMBIENT_SCALE),
				new Vector3f(BLOCK_LIGHT).mul(BLOCK_LIGHT_SCALE),
				level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD
						? SkyAtmosphere.sample(atmosphere, lighting, time, sunDirection, new Vector3f(sunDirection).negate(),
								distanceFog(pack.get().fog(biome.fog())),
								pack.get().fog(biome.fog()).volumetric().get("air"))
						: null);
	}

	/**
	 * The air fog at the edge of the render distance. With Distant Horizons it follows DH's own vanilla fog
	 * toggle (and is on when DH rendering is off): its distant terrain starts where the render distance ends
	 * and isn't drawn by this renderer, so the fog would otherwise leave a ring of sky before it.
	 */
	private static Vector4f distanceFog(FogSettings fog) {
		if (DistantTerrain.source().map(source -> !source.wantsRenderDistanceFog()).orElse(false)) {
			return new Vector4f(0);
		}
		float blocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
		return SkyAtmosphere.distanceFog(fog.distance().get("air"), blocks);
	}

	private static LightingState fallback(float time) {
		Vector3f sunDirection = orbit(time, 0);
		float day = Math.max(sunDirection.y, 0);
		Vector3f sky = new Vector3f(0.15f, 0.2f, 0.3f).mul(day + 0.1f);
		return new LightingState(sunDirection, new Vector3f(day), new Vector3f(sunDirection).negate(),
				new Vector3f(0.12f * Math.max(-sunDirection.y, 0)), sky, new Vector3f(sky).mul(1.5f),
				new Vector3f(0.05f), new Vector3f(BLOCK_LIGHT), null);
	}

	/**
	 * Direction towards the sun. Bedrock's time 0 is noon (straight up); the sun rises in the east (+X) at
	 * 0.75 and sets in the west at 0.25. The orbital offset tilts the path about the east-west axis.
	 */
	static Vector3f orbit(float time, float offsetDegrees) {
		double angle = time * 2 * Math.PI;
		Vector3f direction = new Vector3f((float) -Math.sin(angle), (float) Math.cos(angle), 0);
		return direction.rotateX((float) Math.toRadians(offsetDegrees));
	}

	private static Vector3f linear(Rgba color) {
		return new Vector3f(toLinear(color.r()), toLinear(color.g()), toLinear(color.b()));
	}

	private static float toLinear(float srgb) {
		return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4f);
	}
}
