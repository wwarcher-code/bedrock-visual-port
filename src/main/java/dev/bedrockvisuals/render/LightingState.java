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
		SkyAtmosphere atmosphere,
		float sunIlluminance) {

	/*
	 * Bedrock's own lighting terms, from GPU captures of Bedrock 1.26.60 in the test scene (noon, low sun,
	 * midnight) and its deferred shaders; see docs/bedrock-renderer-notes.md. Values here are Bedrock's divided
	 * by 100 (the noon sun's illuminance becomes 1), before exposure.
	 */
	/** This renderer's units per Bedrock unit. */
	static final float UNIT = 1f / 100f;
	/**
	 * SkyAmbientLightColorIntensity's colour, the same at every time of day in the captures (0.473333,
	 * 0.655911, 1).
	 */
	private static final Vector3f SKY_AMBIENT_COLOR = new Vector3f(0.473333f, 0.655911f, 1f);
	/**
	 * Its intensity is sky_light_indirect_contribution_multiplier (deferred_renderer.json, 0.25) times the
	 * directional light's illuminance plus the ambient illuminance: 25.005 at noon (sun 100), 9.6286 at low sun
	 * (sun 38.4944), 0.105 at midnight (moon 0.4).
	 */
	private static final float SKY_INDIRECT = 0.25f;
	/**
	 * BlockBaseAmbientLightColorIntensity.w: 5.625 (block_light_indirect_contribution_multiplier 0.05 × 112.5),
	 * times the block light colour by level (common.glsl).
	 */
	private static final float BLOCK_LIGHT_INTENSITY = 5.625f;
	/**
	 * The sky's radiance for the exposure meter when Java's sky is drawn (no Bedrock atmosphere: outside the
	 * Overworld or without a pack), relative to its fill colours (deferred.fsh). With Bedrock's sky the meter
	 * reads that sky's own radiance.
	 */
	static final float METER_SKY_SCALE = 0.0417f;
	/** Block light's colour scale; its colour by level is Bedrock's curve, in the shader (common.glsl). */
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
		Rgba horizon = Weather.sky(atmosphere.skyHorizonColor().sample(time));

		Vector3f sunDirection = orbit(time, lighting.orbitalOffsetDegrees().sample(time));
		float sunIlluminance = lighting.sun().illuminance().sample(time);
		float moonIlluminance = lighting.moon().illuminance().sample(time);
		float ambientIlluminance = lighting.ambientIlluminance();
		// Weather greys the sun's and moon's colour like the sky's, at full illuminance (captures in rain and
		// thunder: SunColor (0.404, 0.404, 0.404, 100) and (0.251, 0.251, 0.251, 100), Weather.sky).
		float skyIntensity = lighting.skyIntensity().sample(time);
		float skyAmbient = SKY_INDIRECT * (sunIlluminance + moonIlluminance + ambientIlluminance) * skyIntensity;
		FogSettings fog = pack.get().fog(biome.fog());
		boolean overworld = level.dimensionType().skybox() == DimensionType.Skybox.OVERWORLD;
		SkyAtmosphere sky = overworld
				? SkyAtmosphere.sample(atmosphere, lighting, time, sunDirection, new Vector3f(sunDirection).negate(),
						distanceFog(fog), fog.volumetric().get("air"))
				: null;
		// Under water the sky light takes the water fog's colour (GPU capture 2: SkyAmbientLightColorIntensity
		// (0.222988, 0.673226, 0.938823, 18.35), the FogColor, at the usual intensity) and the atmosphere is
		// Bedrock's underwater one (SkyAtmosphere.underwater).
		Vector3f skyAmbientColor = new Vector3f(SKY_AMBIENT_COLOR);
		FogSettings.DistanceFog water = fog.distance().get("water");
		if (Underwater.submerged() && water != null) {
			skyAmbientColor = srgb(water.color());
			if (sky != null) {
				float blocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
				sky = sky.underwater(SkyAtmosphere.waterFog(water, fog.transitions().get("water"),
						Underwater.secondsSubmerged(), blocks));
			}
		}
		return new LightingState(
				sunDirection,
				// Bedrock sends the sun and moon colours as sRGB/255, not linearised (the captures' SunColor is
				// (1, 0.890196, 0.843137) for the keyframe 255, 227, 215).
				srgb(Weather.sky(lighting.sun().color().sample(time))).mul(sunIlluminance * UNIT),
				new Vector3f(sunDirection).negate(),
				srgb(Weather.sky(lighting.moon().color().sample(time))).mul(moonIlluminance * UNIT),
				skyAmbientColor.mul(skyAmbient * UNIT),
				linear(horizon).mul(skyIntensity * 0.25f),
				linear(lighting.ambientColor()).mul(ambientIlluminance * UNIT),
				new Vector3f(BLOCK_LIGHT).mul(BLOCK_LIGHT_INTENSITY * UNIT),
				sky,
				sunIlluminance);
	}

	/**
	 * The air fog at the edge of the render distance. With Distant Horizons' vanilla fog toggle off (and its
	 * rendering on), the terrain reaches DH's render distance and this renderer lights it, so the fog sits at
	 * the edge of that instead, as Bedrock's sits at the edge of its own render distance.
	 */
	private static Vector4f distanceFog(FogSettings fog) {
		float blocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
		if (DistantTerrain.source().map(source -> !source.wantsRenderDistanceFog()).orElse(false)) {
			int distant = DistantTerrain.source().map(source -> source.renderDistanceBlocks()).orElse(0);
			if (distant <= 0) {
				return new Vector4f(0);
			}
			blocks = Math.max(blocks, distant);
		}
		return SkyAtmosphere.distanceFog(fog.distance().get("air"), fog.distance().get("weather"), blocks);
	}

	private static LightingState fallback(float time) {
		Vector3f sunDirection = orbit(time, 0);
		float day = Math.max(sunDirection.y, 0);
		Vector3f sky = new Vector3f(0.15f, 0.2f, 0.3f).mul(day + 0.1f);
		return new LightingState(sunDirection, new Vector3f(day), new Vector3f(sunDirection).negate(),
				new Vector3f(0.12f * Math.max(-sunDirection.y, 0)), sky, new Vector3f(sky).mul(1.5f),
				new Vector3f(0.05f), new Vector3f(BLOCK_LIGHT), null, day * 100f);
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

	private static Vector3f srgb(Rgba color) {
		return new Vector3f(color.r(), color.g(), color.b());
	}

	private static Vector3f linear(Rgba color) {
		return new Vector3f(toLinear(color.r()), toLinear(color.g()), toLinear(color.b()));
	}

	private static float toLinear(float srgb) {
		return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4f);
	}
}
