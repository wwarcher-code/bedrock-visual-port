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
 * so the scale factors marked TUNE are placeholders until the fidelity pass compares against Bedrock.
 */
public record LightingState(
		Vector3f sunDirection,
		Vector3f sun,
		Vector3f moonDirection,
		Vector3f moon,
		Vector3f sky,
		Vector3f ambient,
		Vector3f blockLight) {

	/** TUNE: Bedrock's noon sun illuminance maps to 1. */
	private static final float SUN_SCALE = 1f / 100f;
	/** TUNE: Bedrock's full moon (0.4) maps to a dim but readable night. */
	private static final float MOON_SCALE = 0.12f / 0.4f;
	/** TUNE: how strongly the sky's zenith color fills shadowed faces. */
	private static final float SKY_FILL = 1.5f;
	/** TUNE: Bedrock's ambient illuminance (0.02 in vanilla) as a floor everywhere. */
	private static final float AMBIENT_SCALE = 5f;
	/** Warm torchlight; Bedrock's local light colors replace it once a pack defines them. */
	private static final Vector3f BLOCK_LIGHT = new Vector3f(1.0f, 0.72f, 0.45f);

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

		Vector3f sunDirection = orbit(time, lighting.orbitalOffsetDegrees().sample(time));
		float sunLevel = lighting.sun().illuminance().sample(time) * SUN_SCALE;
		float moonLevel = lighting.moon().illuminance().sample(time) * MOON_SCALE;
		float skyIntensity = lighting.skyIntensity().sample(time);
		return new LightingState(
				sunDirection,
				linear(lighting.sun().color().sample(time)).mul(sunLevel),
				new Vector3f(sunDirection).negate(),
				linear(lighting.moon().color().sample(time)).mul(moonLevel),
				// The zenith color's own curve already darkens through the evening.
				linear(zenith).mul(SKY_FILL * skyIntensity),
				linear(lighting.ambientColor()).mul(lighting.ambientIlluminance() * AMBIENT_SCALE),
				new Vector3f(BLOCK_LIGHT));
	}

	private static LightingState fallback(float time) {
		Vector3f sunDirection = orbit(time, 0);
		float day = Math.max(sunDirection.y, 0);
		return new LightingState(sunDirection, new Vector3f(day), new Vector3f(sunDirection).negate(),
				new Vector3f(0.12f * Math.max(-sunDirection.y, 0)), new Vector3f(0.15f, 0.2f, 0.3f).mul(day + 0.1f),
				new Vector3f(0.1f), new Vector3f(BLOCK_LIGHT));
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
