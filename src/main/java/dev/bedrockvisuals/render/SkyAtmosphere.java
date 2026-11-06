package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import dev.bedrockvisuals.bedrock.AtmosphereSettings;
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
		Vector4f sun, Vector4f moon, Vector4f fog, Vector4f volumeAir, Vector4f volumeHeight) {
	/** std140 size of the AtmosphereData block (lighting/sky.glsl): ten vec4s, the last written by LightingPass. */
	static final int DATA_SIZE = 10 * 16;

	/**
	 * Bedrock's air medium in plains, which has no volumetric fog settings (the engine's default), from a GPU
	 * capture: AirAlbedoExtinction (1, 1, 1, 0.001), HeightFogScaleBias (-1, 558.38) (full density below
	 * y 557.4), HenyeyGreensteinG.x 0.75.
	 */
	static final Vector4f DEFAULT_AIR_ALBEDO_EXTINCTION = new Vector4f(1, 1, 1, 0.001f);
	static final Vector4f DEFAULT_AIR_HEIGHT = new Vector4f(-1f, 558.38f, 0.75f, 1f);

	/**
	 * Bedrock's air medium for PopulateVolume (AirAlbedoExtinction, HeightFogScaleBias, HenyeyGreensteinG), for
	 * biomes with volumetric settings: albedo {@code s / (s + a)}, extinction {@code max_density × (s + a)} per
	 * block (TUNE: how the engine folds density into the coefficients wasn't captured; plains has none), full density below max_density_height fading to none at
	 * zero_density_height.
	 *
	 * @return {albedo rgb, extinction} and {height scale, height bias, phase g, on}
	 */
	static Vector4f[] volume(FogSettings.VolumetricFog air) {
		if (air == null) {
			return new Vector4f[] {new Vector4f(DEFAULT_AIR_ALBEDO_EXTINCTION), new Vector4f(DEFAULT_AIR_HEIGHT)};
		}
		float sr = air.scattering().x(), sg = air.scattering().y(), sb = air.scattering().z();
		float ar = air.absorption().x(), ag = air.absorption().y(), ab = air.absorption().z();
		float tr = sr + ar, tg = sg + ag, tb = sb + ab;
		float total = (tr + tg + tb) / 3f;
		Vector4f albedoExtinction = new Vector4f(tr > 0 ? sr / tr : 0, tg > 0 ? sg / tg : 0, tb > 0 ? sb / tb : 0,
				air.maxDensity() * total);
		float zero = air.zeroDensityHeight();
		float full = air.maxDensityHeight();
		float scale;
		float bias;
		if (Math.abs(zero - full) < 1e-3f) {
			scale = -1000f;
			bias = zero * 1000f;
		} else {
			scale = 1f / (full - zero);
			bias = -zero / (full - zero);
		}
		Vector4f height = new Vector4f(scale, bias, air.henyeyGreensteinG(), albedoExtinction.w > 0 ? 1 : 0);
		return new Vector4f[] {albedoExtinction, height};
	}

	/**
	 * The sun and moon terms are the lighting's own: the illuminance curve at the (eased) time of day, colour
	 * as sRGB/255. Verified in GPU captures: SunColor (1, 0.628992, 0.320394, 38.4944) at low sun, the curve's
	 * value there; the moon 0.4 at midnight.
	 */
	static SkyAtmosphere sample(AtmosphereSettings atmosphere, LightingSettings lighting, float time,
			Vector3f sunDirection, Vector3f moonDirection, Vector4f fog, FogSettings.VolumetricFog air) {
		Vector4f[] volume = volume(air);
		volume[0].w *= Weather.airExtinctionScale();
		Rgba sunColor = Weather.sky(lighting.sun().color().sample(time));
		Rgba moonColor = Weather.sky(lighting.moon().color().sample(time));
		return new SkyAtmosphere(
				linear(Weather.sky(atmosphere.skyZenithColor().sample(time))),
				linear(Weather.sky(atmosphere.skyHorizonColor().sample(time))),
				new Vector4f(atmosphere.horizonBlendMin().sample(time), atmosphere.horizonBlendStart().sample(time),
						atmosphere.horizonBlendMieStart().sample(time), atmosphere.horizonBlendMax().sample(time)),
				new Vector4f(atmosphere.rayleighStrength().sample(time), atmosphere.sunMieStrength().sample(time),
						atmosphere.moonMieStrength().sample(time), atmosphere.sunGlareShape().sample(time)),
				new Vector4f(sunColor.r(), sunColor.g(), sunColor.b(), lighting.sun().illuminance().sample(time)),
				new Vector4f(moonColor.r(), moonColor.g(), moonColor.b(), lighting.moon().illuminance().sample(time)),
				fog, volume[0], volume[1]);
	}

	/**
	 * Bedrock's air distance fog: terrain fades into the sky (the same atmosphere function, so its colour is
	 * the sky's behind it) from {@code fog_start} to {@code fog_end}, fractions of the render distance (or
	 * blocks, for "fixed" fogs). Off ({@code w} 0) when there's no air fog.
	 */
	static Vector4f distanceFog(FogSettings.DistanceFog air, FogSettings.DistanceFog weather,
			float renderDistanceBlocks) {
		if (air == null || air.end() <= air.start()) {
			return new Vector4f(0);
		}
		float distance = air.relativeToRenderDistance() ? renderDistanceBlocks : 1f;
		// Vibrant Visuals starts the render-distance fog at 0.75, not the fog file's 0.92: the captures'
		// FogAndDistanceControl is (0.75, 4, 384), with the vanilla packs all saying 0.92.
		float start = air.relativeToRenderDistance() ? VIBRANT_FOG_START : air.start();
		float end = air.end();
		// Rain pulls the fog in towards the weather fog (Weather.fogRange), when both are in the same units.
		if (weather != null && weather.relativeToRenderDistance() == air.relativeToRenderDistance()
				&& weather.end() > weather.start()) {
			float[] range = Weather.fogRange(start, end, weather.start(), weather.end());
			start = range[0];
			end = range[1];
		}
		return new Vector4f(start, 1f / Math.max(end - start, 1e-3f), distance, 1);
	}

	static final float VIBRANT_FOG_START = 0.75f;

	/*
	 * Under water Bedrock swaps the atmosphere function's inputs for its own (GPU capture 2, camera under water
	 * at noon): SkyZenithColor (0, 0.2, 0.2), SkyHorizonColor (0, 0.8, 0.8), FogSkyBlend (0, 0.60554, 0.56068,
	 * 0.8), AtmosphericScattering.x 0.5. They're in no pack file. Its distance fog then fades the scene into
	 * that teal, and the sky above the surface is the same function.
	 */
	private static final Vector3f UNDERWATER_ZENITH = new Vector3f(0f, 0.2f, 0.2f);
	private static final Vector3f UNDERWATER_HORIZON = new Vector3f(0f, 0.8f, 0.8f);
	private static final Vector4f UNDERWATER_BLEND = new Vector4f(0f, 0.60554f, 0.56068f, 0.8f);
	private static final float UNDERWATER_RAYLEIGH = 0.5f;

	/**
	 * This atmosphere as Bedrock has it while the camera is under water: the captured underwater sky, and the
	 * water's distance fog (Underwater.waterFog).
	 */
	SkyAtmosphere underwater(Vector4f waterFog) {
		return new SkyAtmosphere(new Vector3f(UNDERWATER_ZENITH), new Vector3f(UNDERWATER_HORIZON),
				new Vector4f(UNDERWATER_BLEND),
				new Vector4f(UNDERWATER_RAYLEIGH, scattering.y, scattering.z, scattering.w), sun, moon, waterFog,
				volumeAir, volumeHeight);
	}

	/**
	 * The water's distance fog ({@code distance.water}: fixed, 0 to 60 blocks in vanilla), grown in by its
	 * transition fog over the time spent under water. Captured about 7 s after going under:
	 * FogAndDistanceControl (0, 9.21555, 384), so the fog ends at 41.7 blocks, which is 60 at the transition's
	 * 0.694 (0.6 at 5 s rising to 1 at 30 s).
	 */
	static Vector4f waterFog(FogSettings.DistanceFog water, FogSettings.TransitionFog transition, float seconds,
			float renderDistanceBlocks) {
		if (water == null || water.end() <= water.start()) {
			return new Vector4f(0);
		}
		float scale = water.relativeToRenderDistance() ? renderDistanceBlocks : 1f;
		float start = water.start() * scale;
		float end = water.end() * scale;
		if (transition != null) {
			FogSettings.DistanceFog init = transition.init();
			float initScale = init.relativeToRenderDistance() ? renderDistanceBlocks : 1f;
			float percent = transition.percent(seconds);
			start = init.start() * initScale + (start - init.start() * initScale) * percent;
			end = init.end() * initScale + (end - init.end() * initScale) * percent;
		}
		return new Vector4f(start, 1f / Math.max(end - start, 1e-3f), 1f, 1);
	}

	/** Writes AtmosphereData; {@code null} writes a disabled block (the shader leaves Java's sky). */
	/** a mixed towards b by t, every term (BiomeMix); either alone where the other has none. */
	static SkyAtmosphere mix(SkyAtmosphere a, SkyAtmosphere b, float t) {
		if (a == null || b == null) {
			return a == null ? b : a;
		}
		return new SkyAtmosphere(new Vector3f(a.zenith).lerp(b.zenith, t), new Vector3f(a.horizon).lerp(b.horizon, t),
				new Vector4f(a.blendStops).lerp(b.blendStops, t), new Vector4f(a.scattering).lerp(b.scattering, t),
				new Vector4f(a.sun).lerp(b.sun, t), new Vector4f(a.moon).lerp(b.moon, t),
				new Vector4f(a.fog).lerp(b.fog, t), new Vector4f(a.volumeAir).lerp(b.volumeAir, t),
				new Vector4f(a.volumeHeight).lerp(b.volumeHeight, t));
	}

	/** Float index of the height fog's scale in the block write() fills; its bias follows. */
	static final int VOLUME_HEIGHT_SCALE = 8 * 4;

	/**
	 * After blending: the height fog's bias made relative to the camera, as the shader reads it
	 * (density = saturate(scale × (cameraY + y) + bias)).
	 */
	static java.nio.ByteBuffer cameraRelative(java.nio.ByteBuffer data) {
		java.nio.ByteBuffer floats = data.duplicate().order(java.nio.ByteOrder.nativeOrder());
		int at = floats.position() + VOLUME_HEIGHT_SCALE * 4;
		double cameraY = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position().y;
		floats.putFloat(at + 4, (float) (floats.getFloat(at + 4) + floats.getFloat(at) * cameraY));
		return data;
	}

	static void write(Std140Builder builder, SkyAtmosphere sky) {
		if (sky == null) {
			for (int i = 0; i < 9; i++) {
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
		builder.putVec4(sky.volumeAir);
		// The biome's (scale, bias, phase g, on): blended as they are; LightingPass then moves the bias to the
		// camera (cameraRelative), as the shader works relative to it.
		builder.putVec4(sky.volumeHeight.x, sky.volumeHeight.y, sky.volumeHeight.z, sky.volumeHeight.w);
	}

	private static Vector3f linear(Rgba color) {
		return new Vector3f(toLinear(color.r()), toLinear(color.g()), toLinear(color.b()));
	}

	private static float toLinear(float srgb) {
		return srgb <= 0.04045f ? srgb / 12.92f : (float) Math.pow((srgb + 0.055f) / 1.055f, 2.4f);
	}
}
