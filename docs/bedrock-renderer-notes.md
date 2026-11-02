# Bedrock renderer notes

How Vibrant Visuals (Bedrock 1.26.60) computes things, learned by disassembling the compiled shaders in the
installed game's `data\renderer\materials` (RenderDragon `.material.bin`, format version 26, Direct3D SM6.5
DXIL) for this private project. These notes describe behaviour in our own words; no Mojang code or
disassembly is kept in the repository. Values marked *runtime* are uniforms the engine computes on the CPU,
so the shaders don't contain them; they're to be found in the game executable next.

## Pipeline (materials involved)

- `RenderChunkPrepass` / `WaterPrepass`: geometry into the G-buffer.
- `DeferredMixedResolutionLightingPrepass`: `DirectionalLightingPass` (sun/moon) and
  `DiscreteIndirectCombinedLightingPass` (sky, ambient, block light); `DeferredMixedResolutionSurfaceRadiance`
  upscales.
- `DeferredIndirectSpecular` + `ScreenSpaceReflections` (`SSRRayMarchHZB`, `SSRGetReflectedColor`,
  `SSRFillGaps`): reflections.
- `WaterExtinction`, `DeferredWater`, `DeferredMixedResolutionCaustics`: water.
- `VolumeScattering`, `VolumetricsShadows`: volumetric fog.
- `LuminanceHistogram` (auto exposure), `LocalExposure`, `Bloom`, `ColorPostProcessing` (grading, tone map).
- `WaterSimulation`: an FFT ocean simulation, only used when a water preset enables waves (vanilla doesn't).

## G-buffer

Targets written by the geometry prepass (water's `DoWaterSurfaceBuffer` pass):

- **Target 0 (RGBA8 as uint):** two 8-bit material values packed in x (y holds linear roughness, read by the
  water shading as `(x >> 8) & 255`), the light colour in y and z as a shared-scale encoding (each channel
  divided by 6, a common scale stored alongside), and the sky light level in w.
- **Target 1 (float):** the colour texture's RGB, and in w two quantities packed by sign around 0.5.
- **Target 2 (float):** the normal, octahedral-encoded in xy, and the screen-space motion vector in zw.

Bedrock's own names for the shading inputs: `ColorMetalnessSubsurface`, `EmissiveAmbientLinearRoughness`,
`Normal`, `SceneDepth`.

### Block light colour

Where a vertex has no explicit light colour, it's derived from the block light level `l` (0 to 1):
`R = l²`, `G = l² (0.64 + 0.36 l²)`, `B = l² (0.4 + 0.6 l⁴)`. Near a torch the light is almost white; as it
falls off it turns orange, then red.

### Normal maps

Texture-set normal maps are sampled with explicit mip levels and flattened as the mip rises (the
perturbation scales with `1 - saturate(mip)²`), so distant surfaces go smooth. Height maps are turned into
normals from a 2x2 gather of exact texels, nudged by 1/16 of a texel, so the result stays texel-exact.

## Texel quantization ("pixel-grid lock")

The lighting passes rebuild each pixel's world position, take the face normal from screen-space
derivatives, round it to the nearest axis (`QuantizationPrecisionRoundingParameters.x`), and snap the
position to the centre of its texel on that face, on a grid of `QuantizationParameters.z` (1/16 of a block
for 16x textures, matching `shadows/global.json`'s `texel_size`). Shadows and lighting are evaluated at that
snapped point, so every texel gets one value and shadow edges follow the texel grid.

## Shadows

Cascaded shadow maps, up to 8 cascades (`CascadesShadowProj[8]`, `CascadesParameters[8]`). The lookup point
is the snapped texel centre pushed along the normal, with a slope bias of `tan(acos(N·L))` times a per-cascade
factor. Filtering is a square PCF kernel of 1x1 to 9x9 gathers (per-cascade width), each gather bilinearly
weighted. Cloud shadows and a separate first-person player shadow map exist.

## Directional light (sun and moon)

`DirectionalLightingPass` writes diffuse and specular separately. Light is in physical units: the light's
colour times its illuminance from the lighting JSON (100 at noon), applied as is; exposure then brings the
image to the screen.

- **Diffuse:** `albedo (1 - metalness) / π × colour × illuminance × (1 - F) × N·L × shadow`, where for
  materials with subsurface the N·L is blended (by the subsurface value times a global contribution) towards
  a wrapped `(N·L + w) / (1 + w)²`, plus a back-lit term with `-N`.
- **Specular:** Cook-Torrance as for water, with `F0 = mix(0.04, albedo, metalness)` and Schlick Fresnel on
  `V·H`.
- **Sky light heuristic:** with `DirectionalLightSkyLightHeuristicToggles.x` on, pixels with zero sky light
  get no sun or moon at all (no light leaking into caves through gaps in the shadow map).
- The texel snapping applies when `QuantizationParameters.y` is above 0.

## Indirect (ambient) light

`DiscreteIndirectCombinedLightingPass` (without GPU block lighting) computes the ambient irradiance as
`max(block + sky, AmbientLightParams.rgb × AmbientLightParams.w)`, reduced for emissive pixels:

- **Block:** the vertex light colour from the G-buffer (shared-scale encoding, range 6) times
  `BlockBaseAmbientLightColorIntensity.w`.
- **Sky:** `s × mix(s⁴, s², CameraLightIntensity.y)` times `SkyAmbientLightColorIntensity.rgb × .w`, with `s` the
  sky light level. This is one colour, independent of the surface normal; directional sky light comes from
  the sky probe (`SkyAmbientSamples`), to analyse.

*Runtime:* `SkyAmbientLightColorIntensity`, `AmbientLightParams`, `BlockBaseAmbientLightColorIntensity`,
`CameraLightIntensity`.

## Exposure

The lighting passes end by multiplying by `0.18 / PreviousFrameAverageLuminance + 0.0001` when
`PreExposureEnabled` (pre-exposure, undone in `ColorPostProcessing`); auto exposure keys the average
luminance to middle grey 0.18.

`LuminanceHistogram` measures the average:

- **Histogram:** each pixel's luminance (Rec. 709 weights, pre-exposure removed) goes into one of 254 bins
  of `log2` luminance between `MinLogLuminance` and `+ LogLuminanceRange`; below 0.00095 it lands in bin 0,
  which is ignored. Each pixel counts `exp(-CenterWeight × r²)`, r its distance from the screen centre in UV.
- **Average:** the weighted mean bin over the part of the histogram between the `QuantileBounds` fractions
  of the total (the darkest and brightest pixels are dropped), converted back to luminance.
- **Adaptation:** in log space, `L += (target - L) × (1 - exp(-Adaptation.y × AdaptiveParameters.x × rate))`,
  with `rate` = `AdaptiveParameters.y` when brightening and `.z` when darkening (`Adaptation.y` is the frame
  time).

*Runtime:* every parameter above.

## Bloom

Thresholded at `BloomParams.y × average luminance` (a 5-tap downsample that keeps samples above it), blurred
by dual-filter down and up sampling, and added to the HDR image as `+ BloomParams.x × bloom` before the
tone map. *Runtime:* both parameters.

## Reflections (`DeferredIndirectSpecular`)

- **Sky reflection:** a pre-convolved sky cubemap (`SpecularIBLRecords`, mip chosen by roughness), faded by
  the sky light level: `saturate((15 s - fadeStart) / max(fadeEnd - fadeStart, 1))³ × IBLParameters.x`,
  from `IBLSkyFadeParameters`.
- **Screen-space reflections:** a separate buffer from the `ScreenSpaceReflections` passes (hierarchical depth
  ray march, then gap filling), blended over the sky reflection by its confidence. Per Mojang's docs SSR is
  mirror-only, fading from roughness 0.3 to none at 0.4.
- **Block light** adds an indirect specular term (`BlockLightIndirectSpecularIntensity`).
- **Texel snapping:** with `QuantizationParameters.w` on, the reflection's start point is snapped to the texel
  centre like the lighting, so each texel reflects one value. Together with per-texel roughness and normals,
  this makes reflections pixelated.

## Colour pipeline (`ColorPostProcessing`)

1. The lighting passes write pre-exposed colour (`× 0.18 / avg`); this pass divides that back out.
2. Average luminance `avg`: the histogram's value clamped to `LuminanceMinMax` (0.18 when auto exposure is off).
3. Exposure key, by `ExposureCompensation.x`: mode 1 is `1.03 - 2 / (log10(avg + 1) + 2)`; mode 2 or more
   reads a curve texture at the normalised log luminance between the min and max; otherwise the constant
   `ExposureCompensation.y`.
4. Colour grading (temperature, then per-band saturation, contrast, gain, offset around the average
   luminance) on the HDR colour.
5. Exposure: `× 0.18 / avg × key`.
6. Tone map, by `TonemapParams0.x`, with the white point `W = max(whitePoint, minWhitePoint) × exposure`:
   0 Reinhard `c / (1 + c)`; 1 extended Reinhard per channel `c (1 + c / W²) / (1 + c)`; 2 the same on
   luminance, scaling the colour; 3 Hable (A 0.15, B 0.5, C 0.1, D 0.2, E 0.02, F 0.3) on `2c`, normalised
   by its value at `W²`; 4 ACES (Hill's fit, with its input and output matrices); 5 generic: Lottes'
   peak-channel curve (`p^a / (p^a × b + c)` on the brightest channel, the per-channel ratios raised to
   `1/crosstalk`, pulled towards white by the mapped peak to the power `w`, then raised back) with
   *runtime* parameters `GenericTonemapperContrastAndScaleAndOffsetAndCrosstalk` and
   `GenericTonemapperCrosstalkParams` (vanilla's JSON only names the operator).
7. Gamma grading after the tone map, per luminance band (shadow, midtone, highlight), default 2.2.

## Water

Order: the scene beneath is attenuated by `WaterExtinction`, then `DeferredWater` blends the surface on top.

- **Extinction:** `T = exp(-σ (d_scene - d_surface + shift))` per RGB channel, with d the view distance
  (capped at the fog distance) and `shift` from `WaterSurfaceEnabledAndExtinctionDistShift.y`. σ comes per
  biome, blended bilinearly across biome borders; it is *runtime*, computed from the water preset's particle
  concentrations.
- **Surface:** specular only, no diffuse; the water's colour texture is not drawn. Output alpha is the
  Fresnel term `F = 0.02 + 0.98 (1 - N·V)⁵`, so the surface blends over the attenuated scene by F.
  - Sun/moon: Cook-Torrance, GGX with `α = r²` (`r = max(roughness, 0.05)`), Smith-Schlick with `k = α/2`,
    Schlick Fresnel with F0 0.02.
  - Ambient specular: `F × max(sky ambient × sky curve + block light, ambient floor)`, faded out by the sky
    reflection (`IBLSkyFadeParameters`, cubed fade on the sky light level), so it only stands in where sky
    reflections don't reach.
  - Volumetric fog from the scattering volume is applied on top (in-scatter times alpha plus transmittance
    times colour).
  - Underwater: refraction index 1.33; outside Snell's window (`1.7689 (1 - (N·V)²) > 1`) total internal
    reflection makes the surface fully reflective.
- **Texture set:** vanilla water uses `water_still_grey` / `water_flow_grey`: a normal map (32 frames of
  16x16, 2 ticks per frame; tilts of about 2° on average, at most 10°, in horizontal streaks) and MERS with
  metalness 0.07 and roughness 0.06 to 0.34 per texel. Screen-space reflections only apply below roughness
  0.3 (fading out by 0.4), so the per-texel roughness and normals make the reflections break up on the
  texel grid.
- **Reflections:** from the separate SSR and indirect specular passes (to analyse), not the water shader.
- **Caustics:** a multiplier on light reaching surfaces under water:
  `(2 × tex(world XZ × scale, frame) × saturate(N·up))^power × (power + 1)`, the `(power + 1)` keeping the
  average about the same as the pattern sharpens. The texture is the engine's built-in 64-frame sheet (not
  in Mojang's samples); frame length, scale and power come from the water preset (vanilla: 0.09 s, 0.5, 1).

## Engine configuration files

The *runtime* values mostly come from JSON files in the install (`Content\data`, found by name in
`Minecraft.Windows.exe`), not the resource packs:

- `renderer/average_luminance.json`: `min_log_luminance` -3, `log_luminance_range` 20 (so log2 luminance
  -3 to 17), `low_percent` 80 and `high_percent` 100 (the average is taken over the brightest 20% of the
  centre-weighted histogram only), `adaptive_time_coefficient` 1, `light_adaptation` 1, `dark_adaptation` 1.
- `renderer/tone_mapping.json`: `exposure_compensation_type` "constant" with `exposure_compensation` 0
  (a custom curve is defined but unused), `min_luminance` -3 and `max_luminance` 4 (the average luminance
  clamp, log2), `f_stop` 19, `min_white_point` 1.
- `renderer/deferred_renderer.json`: indirect multipliers for block light 0.05, sky light 0.25 and image-based
  (sky) lighting 0.5; `emissive_brightness_multiplier` 1; IBL fades between light levels 15 and 12;
  `volumetric_fog_min_ambient_value` 0.
- `bloom_configuration.shared.json`: bloom on, `intensity` 0.02.
- `deferred_lighting_configuration.shared.json`: local exposure and biome blending on,
  `water_extinction_distance_shift` 0.
- `reflection_probe_regen_configuration.shared.json`: the sky reflection probe is regenerated every 600
  frames, 100 game ticks, 500 blocks moved, or when sky or block brightness change by 3 or 16.
- `renderer/atmospheric_weather.json`: rain and thunder sky and cloud colours, sun strength 0 in weather.

How `tone_mapping.json` reaches the shader (from the per-frame setter, which uses the CRT's `exp2`/`exp2f`):
`ExposureCompensation.y = 2^exposure_compensation` (a key of 1 for vanilla); the average-luminance clamp is
`0.125 × 2^min_luminance` to `0.125 × 2^max_luminance` (EV100 to luminance: 0.0156 to 2.0 for vanilla); the
white point is `0.18 × 2^f_stop` (19 stops above middle grey), floored at `min_white_point`.

The generic tone mapper's inputs are not in these files: they are compiled-in defaults of the same settings
struct (found as its default block in `.rdata`, right after `f_stop` 19 and `min_white_point` 1): contrast
1, mid-in 0.3, mid-out 0.18, HDR max 500, crosstalk 25 (`GenericTonemapperContrastAndScaleAndOffsetAndCrosstalk.w`)
and crosstalk saturation 8 (`GenericTonemapperCrosstalkParams.x`). With these, `b = 0.997265`, `c = 1.3675`:
0.3 maps to 0.18 and 500 to 1. The engine computes `b` and `c` from them as in Lottes' curve (`b = (Ph m - Pm) / ((Ph - Pm) m)`,
`c = Ph (1 - b)`, with `Pm = midIn^a`, `Ph = hdrMax^a`, `m = midOut`) and sends `(a, b, c, crosstalk)`.

## Atmosphere (sky colour and fog)

`SkyProbeDeferredShading` (the pass that lights the sky reflection probe) and the fog both use one sky
radiance function per view direction `d`. It takes the atmosphere settings as uniforms: `FogSkyBlend`
(the horizon blend stops), `AtmosphericScattering` (x Rayleigh strength, y sun Mie strength, z moon Mie
strength, w sun glare shape), `SkyZenithColor`, `SkyHorizonColor`, and `SunColor`/`MoonColor` (rgb colour,
w illuminance):

- **Horizon weights.** `b = (1 - smoothstep((d.y - (x - w)) / (y - (x - w))))³` blends the base colour from
  the horizon colour at `d.y = x - w` to the zenith colour at `d.y = y`. `a = (1 - smoothstep((d.y - x + w) / (z - x)))³`
  limits the Mie glow to near the horizon. The component order is assumed to follow the JSON's
  `horizon_blend_stops` (x min, y start, z mie_start, w max); the setter wasn't traced.
- **Rayleigh.** `base × 0.75 × rayleigh × ((1 + cos²θs) Is + (1 + cos²θm) Im)`, with θ the angle to the sun
  or moon and I its illuminance.
- **Mie.** For the sun, `x = saturate(max(cosθs, 0)^glare)` and
  `sun mie strength × Is × x × sun colour × 0.0361 / (1.81 - 1.8x)^1.5` (a Henyey-Greenstein lobe with g = 0.9);
  the same for the moon. It's multiplied by `a` and the horizon colour.
- **Total.** `(rayleigh + mie) / 4π`. The probe can blend towards the underground fog colour, and when
  pre-exposure is on it's scaled by 0.003314.

In the fog, view vectors below `d.y = 0.1` can be clamped up to it (`ClampViewVectors`).

**Terrain sky light doesn't come from it directly.** In `DiscreteIndirectCombinedLightingPass` the sky part of
the ambient is one uniform colour, `SkyAmbientLightColorIntensity`, with no dependence on the surface normal.
The deferred specular pass samples the probe only for reflections (`SpecularIBLRecords`, a mip per
roughness), not for diffuse. `SkyAmbientSamples` is a 3D texture the water uses for sky visibility.

In the executable, the uniform's colour is copied from a field of the directional light record, filled from a
light component's colour under a component flag. Its intensity is the product of an accumulated scalar, a
lighting setting and a value that is passed through a function first. These weren't traced further.

**Fit against the reference screenshots.** The hemisphere integral of this radiance function was fitted to
the four test-scene shots (grass and wall at noon, afternoon, low sun and midnight) in three forms:

- as the uniform sky colour;
- as the normal-dependent irradiance;
- as only the colour, with the current brightness.

All three fit worse than the current stand-in, the zenith colour times sky intensity (squared error about
1810 to 2670, against 1620). The integral drops about tenfold between noon and the low-sun shot. Bedrock's
grass dims only about twofold there, and its walls in shade stay as blue as the zenith colour.

A uniform ambient of the sun's colour times its illuminance was also tried. The fit gave it zero weight.

So the renderer keeps the zenith-colour sky light.

**The sky itself is this function.** `SurfaceRadianceUpscalePass` (the pass that composites the lit surfaces)
treats pixels at the far plane as sky. It skips their lighting, and its distance fog, at full strength there,
replaces them with the radiance above, times the pre-exposure. So Vibrant Visuals' sky is the atmosphere
function, exposed and tone mapped like the terrain, with the sun, moon and stars drawn on top.

As a check, the function with the JSON's curves, colours converted to linear and the daytime exposure clamp
predicts the reference noon shot's sky closely: (125,165,226) against (138,166,224) high up, and
(177,194,223) against (182,193,224) at the horizon.

**The sun and moon terms aren't the lighting's illuminance.** In the executable, the `SunColor` and
`MoonColor` sent with the atmosphere come from the directional lights' records. Each is faded by its light's
elevation, between two angles from the renderer settings (not read). The reference shots agree:

- Bedrock's afternoon sky is as bright as its noon sky, though the sun's illuminance curve has fallen to 68 by
  then.
- The low-sun sky's upper part needs about 4 times the curve's 23.6.

The light's peak (100), faded from 0 at -10° to 1 at +10°, fits noon, afternoon and the upper low-sun sky
(TUNE: the angles). The colours are linear: sending the JSON's sRGB values unconverted fits far worse.

The horizon blend stop mapping (x min, y start, z mie start, w max) fits the shots best of all 24 orders.

Still off: just above the horizon at low sun, Bedrock's sky is lilac, about (164,151,171), where the function
gives peach, about (212,149,148). Bedrock's volumetric fog, which scatters into the sky too, isn't modelled.

The renderer draws it the same way: the lighting pass shades sky pixels with it, Java's sky gradient and
sunset fan are skipped, and Java's sun, moon and stars stay. Rain and other weather don't change it yet;
`atmospheric_weather.json` holds Bedrock's weather variants.

## Still to analyse

`NdLFloor`, the SSR ray march itself, volumetric fog and local
exposure; the built-in caustics texture; and every *runtime* value, from the game executable.
