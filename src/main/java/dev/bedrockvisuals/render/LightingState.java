package dev.bedrockvisuals.render;

import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.BedrockPack;
import dev.bedrockvisuals.bedrock.ClientBiome;
import dev.bedrockvisuals.bedrock.LightingSettings;
import dev.bedrockvisuals.bedrock.Rgba;
import dev.bedrockvisuals.bedrock.TimeOfDay;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Vector3f;

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
		Vector3f blockLight) {

	/** TUNE: Bedrock's noon sun illuminance maps to 1. */
	private static final float SUN_SCALE = 1f / 100f;
	/*
	 * The factors below were fitted to Bedrock 1.26.60 itself: the test scene's grass (lit from above) and
	 * its pillar's camera-facing wall (sky, ground bounce and torch only), measured in Vibrant Visuals
	 * screenshots at noon, afternoon, low sun and midnight, against this renderer's model of the same pixels
	 * (sun, sky fill, bounce, ambient, moon, block light, grading, and Bedrock's exact generic tone map). The
	 * fit lands within about 10 sRGB levels per channel. These stand in for Bedrock's physical light units and
	 * histogram auto exposure (docs/bedrock-renderer-notes.md) until those are rebuilt. Bedrock lights far more from the sky and its ambient floor than from the sun,
	 * which is why its orange evening sun barely tints the ground, and walls in shade take the sky's blue.
	 */
	/** Bedrock's full moon (0.4) is a faint light; the night is mostly the ambient floor. */
	private static final float MOON_SCALE = 0.053f;
	/** How strongly the sky's zenith color fills faces that see the sky. */
	private static final float SKY_FILL = 1.211f;
	/** Bedrock's ambient illuminance (0.02 in vanilla) as a floor everywhere. */
	private static final float AMBIENT_SCALE = 1.9f;
	/**
	 * A stand-in for Bedrock's automatic exposure: a base exposure, brightened in proportion as the key light
	 * (the sun and moon level) fades, by at most {@link #MAX_COMPENSATION}. The fit wants almost none: Bedrock's
	 * daylight is mostly sky and ambient, which don't fade with the sun.
	 */
	private static final float BASE_EXPOSURE = 5.865f;
	private static final float MAX_COMPENSATION = 1.022f;
	/**
	 * Block light, unlike the sky, is the same at noon and midnight, so exposure is what makes a torch glow at
	 * night and vanish by day. Its strength goes as this over the key light, floored at {@link #MIN_BLOCK_KEY}:
	 * about 0.03 at noon, 0.65 at night.
	 */
	private static final float BLOCK_LIGHT_SCALE = 0.024f;
	private static final float MIN_BLOCK_KEY = 0.021f;
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
		Rgba zenith = pack.get().atmosphere(biome.atmosphere()).skyZenithColor().sample(time);
		Rgba horizon = pack.get().atmosphere(biome.atmosphere()).skyHorizonColor().sample(time);

		Vector3f sunDirection = orbit(time, lighting.orbitalOffsetDegrees().sample(time));
		float sunLevel = lighting.sun().illuminance().sample(time) * SUN_SCALE;
		float moonLevel = lighting.moon().illuminance().sample(time) * MOON_SCALE;
		float skyIntensity = lighting.skyIntensity().sample(time);
		float exposure = exposure(sunLevel + moonLevel);
		return new LightingState(
				sunDirection,
				linear(lighting.sun().color().sample(time)).mul(sunLevel * exposure),
				new Vector3f(sunDirection).negate(),
				linear(lighting.moon().color().sample(time)).mul(moonLevel * exposure),
				// The sky color curves already darken through the evening.
				linear(zenith).mul(SKY_FILL * skyIntensity * exposure),
				linear(horizon).mul(SKY_FILL * skyIntensity * exposure),
				linear(lighting.ambientColor()).mul(lighting.ambientIlluminance() * AMBIENT_SCALE * exposure),
				new Vector3f(BLOCK_LIGHT).mul(blockLightStrength(sunLevel + moonLevel)));
	}

	static float blockLightStrength(float keyLight) {
		return BLOCK_LIGHT_SCALE / Math.max(keyLight, MIN_BLOCK_KEY);
	}

	static float exposure(float keyLight) {
		return BASE_EXPOSURE * Math.min(1f / Math.max(keyLight, 1e-4f), MAX_COMPENSATION);
	}

	private static LightingState fallback(float time) {
		Vector3f sunDirection = orbit(time, 0);
		float day = Math.max(sunDirection.y, 0);
		Vector3f sky = new Vector3f(0.15f, 0.2f, 0.3f).mul(day + 0.1f);
		return new LightingState(sunDirection, new Vector3f(day), new Vector3f(sunDirection).negate(),
				new Vector3f(0.12f * Math.max(-sunDirection.y, 0)), sky, new Vector3f(sky).mul(1.5f),
				new Vector3f(0.05f), new Vector3f(BLOCK_LIGHT));
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
