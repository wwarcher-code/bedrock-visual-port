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

Verified in the Bloom material's four passes:

- **ThresholdedDownSample.** Half resolution: `0.5 × centre + 0.125 × four diagonal taps 3 source texels
  out`. Each tap is zeroed if its luminance is below `BloomParams.y × average luminance`.
- **DFDownSample.** The same kernel, without the threshold, repeated down the chain.
- **DFUpSample.** `0.166 ×` four diagonal taps 1 texel out, plus `0.083 ×` four 2 texels out, from the
  level below only, with no mixing with the same-level downsample.
- **BloomBlend.** One more upsample, added to the HDR scene as `scene + BloomParams.x × bloom`.

`BloomParams.x` is the configuration's `intensity`, 0.02. The threshold factor `BloomParams.y` and the
number of levels are runtime values. The renderer uses a threshold of exposed luminance 1 and 5 levels below
half resolution (TUNE), blooming the lighting pass's new HDR output. It adds the difference bloom makes after
exposure and tone mapping to the main image.

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

## Under water (verified)

- **Caustics are a per-frame switch.** `DeferredMixedResolutionCaustics` writes the multiplier for every
  pixel (after `ClearCausticsMultiplier` resets it to 1). The direct-lighting pass multiplies the light's
  illuminance by it. The forward entity shaders compute the same multiplier inline, gated only by
  `CausticsParameters.x && .w`, with no water test. So caustics are on for every surface while the
  camera is under water, and off otherwise.
  - The pattern is `renderer/caustics.png` in the install: 32×2048, 64 frames of 32×32.
  - It's sampled at world XZ × `scale` (0.5, so one tile every 2 blocks, 16 texels a block).
  - It's multiplied by `2 × saturate(N·up)`, raised to `power`, then times `power + 1`.
- **WaterExtinction** multiplies the scene by `exp(-σ × (d_scene - d_water))` per channel, where `d_water` is
  the water depth texture's distance and both distances are capped at the fog distance. From under water,
  the path starts at the camera. The pass adds no in-scattering; that comes from the volumetric fog's water
  medium.
- **DeferredWater from below.** Refraction index 1.33. Outside Snell's window
  (`1.7689 (1 - (N·V)²) > 1`), total internal reflection makes the surface a mirror.

- **From above water.** The caustics shader has no water test. The engine limits the pass with the stencil:
  in captures, `CausticsParameters` is (1, 0.5, 1, 1) and the pass (before the main lighting) is a
  full-screen quad with depth test off and stencil EQUAL 1, so only water-covered pixels are written. A
  grass pixel fails the stencil, and a pool-floor pixel is written. The multiplier texture is cleared to 1
  first. `CausticsTextureParameters.y` is the frame (46, 49, 35 in the three captures). PopulateVolume's water medium
  also lies between the water's front and back depths, so the light shafts in water show from above.

The renderer applies caustics to every surface while the camera's fluid is water
(`Camera.getFluidInCamera()`). From above, it applies them, and the water medium in the volume march, to
points inside the top water body of their column. That comes from a 256×256 map of water surface and
bottom heights around the camera (`WaterColumns`), standing in for the water depths Bedrock has. Its lighting
runs before Java draws the water. The water pass then dims what's behind the surface by the whole path's
extinction, so the march adds the water's scattering from above without its extinction. It
multiplies the scene, including the sky seen through the surface, by the water's transmittance along the
view, capping the path where the ray leaves through the surface above the camera. Seen from below, the
water surface passes the air beyond inside Snell's window and mirrors the water below outside it.

## Volumetric fog (verified in PopulateVolume)

A froxel grid of 8×8-pixel tiles and 96 depth slices (PC ultra, out to 250 blocks), with exponential slice
spacing `(2^(5.77 z) - 1) × 0.01866`. For each froxel, PopulateVolume works out the medium, then the
in-scattered light:

- **Medium.**
  - Water inside the water volume, judged from the water front and back depth (camera underwater
    flips it): `WaterAlbedoExtinction`, per biome.
  - Air elsewhere: extinction `AirAlbedoExtinction.w × saturate(HeightFogScaleBias.x × y + .y)` and
    scattering = extinction × albedo.
  - The Henyey-Greenstein g is blended between air and water (`HenyeyGreensteinG.xy`).
  - Scattering is multiplied by `1 - distance fog`, so volumetrics give way to the distance fog.
- **Light.**
  - Ambient: `max(block light × block intensity + sky ambient × sky visibility, MinAmbientValue) / 4π`.
  - Plus the directional light × HG(g, `dot(L, -V)`) × the froxel's cascaded shadow.
  - Both are times the scattering.
- VolumeScattering integrates front to back. The composite does `scene × transmittance + in-scatter`, for the
  sky too.

The air values come from each biome fog's `volumetric` block: `max_density`, `zero_density_height` and
`max_density_height`, then the `media_coefficients` scattering and absorption, and `henyey_greenstein_g`.
Plains and the default have none, and the compiled-in default wasn't found. Many biomes set
`max_density` 0 explicitly, so the default isn't 0. The renderer uses the most common explicit values
(0.05, scattering 0.04, g 0.6) as a TUNE default.

The renderer ray-marches the same model per pixel: 48 steps with interleaved-gradient jitter, through its
shadow map and cloud shadows.

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

**Distance fog.** The same pass blends the lit surface towards that radiance by
`saturate((RenderChunkFogAlpha.x - FogAndDistanceControl.x + distance / FogAndDistanceControl.z) × FogAndDistanceControl.y)`.
This matches the fog JSON's air fog: x is the start, y is 1 / (end - start), and z is the distance they're
fractions of (the render distance for `"render"` fogs). The default air fog runs from 0.92 to 1.0. With
atmospheric scattering on, the fog's colour is the sky's, not `fog_color`, so terrain fades into the sky
behind it.

The renderer draws it the same way: the lighting pass shades sky pixels with it, Java's sky gradient and
sunset fan are skipped, and Java's sun, moon and stars stay. Rain and other weather don't change it yet;
`atmospheric_weather.json` holds Bedrock's weather variants.

## Clouds

`CloudsForwardPBR` (pass `ForwardPBRTransparent`) lights each cloud cell face. The cloud layer spans
192.33 to 196.33 (relative to `WorldOrigin.y`). The vertex shader passes the position inside the cell and a
mask of which neighbouring cells are filled, and paths through the cloud continue into filled neighbours,
capped at 16 blocks. With `p` the camera-relative position, `V` the view direction (camera to fragment), `L` the
shadow-casting light's direction and colour × illuminance `E`, and the cloud colour `C`:

- **Ambient.** `SkyAmbientLightColorIntensity.rgb × .w × C`.
- **Scattered light** (`CloudLightingToggles.y`). `s` is the view's path inside the cloud from this face; from
  the middle of it, `t` is the light's path. Scattering is
  `HG(g, dot(L, -V)) × (1 - smoothstep(saturate(s/2 / U.x))) × exp(-t × U.w) × E`, with `U` =
  `CloudLightingUniforms`, `g = U.y` and `HG = (1 - g²) / 4π / (1 + g(2cos + g))^1.5`. It isn't multiplied
  by `C`.
- **Wrapped diffuse** (`CloudLightingToggles.x`). With the wrap `w` (`SubsurfaceScattering…Falloff.y`):
  `(max((N·L + w)/(1+w)², 0) + max((-N·L + w)/(1+w)², 0) × (1 - smoothstep(saturate(s / U.x)))) / π × C × E`,
  so thin parts glow from behind.
- **Reflected sky.** The probe at the reflection direction, at its roughest mip, times the BRDF LUT at F0 0.04,
  times `U.z`.
- **Distance.** Mixed towards the atmosphere radiance by `saturate(distance / DistanceControl.x - 0.9)`.
  Volumetric scattering applies on top.
- The alpha is the vertex colour's.

`CloudLightingUniforms`, `CloudLightingToggles` and the wrap are runtime values, read from GPU captures:
U = (16, 0.5, 5, 0.0625), wrap 0.307, `CloudColor` alpha 0.9 (see "Runtime values from GPU captures").

The renderer applies this to Java's cloud mesh (12×12×4 cells at the same height). Java's mesh has no
neighbour mask, so the paths walk Java's cloud field instead (the cell texture the cloud shadows use),
continuing through filled neighbours up to 16 blocks. Java builds the mesh from a circle of cells around the
camera, and cells at its edge come and go as the clouds drift. So the sky blend and a fade-out finish 1.5 cells
inside it, over the last quarter of the mesh's radius, much closer than Bedrock's 1152 blocks. Distant
Horizons' own clouds are hidden for the session while these are on (through its API override, so its saved
setting is untouched). Distant Horizons also sets Java's Clouds option to OFF whenever its renderer starts
(its "override vanilla graphics settings" option, on by default), which a mixin prevents.

**Far clouds.** Bedrock's cloud field reaches 3× the render distance. With Distant Horizons (and later Voxy)
the terrain reaches far past Java's cloud range. Beyond Java's mesh, the lighting pass traces each sky ray
through the same drifting cloud field (up to 64 cells), out to the larger of 3× the render distance and
Distant Horizons' render distance. It lights the first filled cell with the same lighting
(`cloud_lighting.glsl`) and blends it into the sky by Bedrock's fog. It fades in exactly where the mesh fades
out. Far clouds are only traced over sky pixels: they don't yet know Distant Horizons' terrain depth, so a far
mountain above the horizon could show a cloud that should be behind it.

Water and clouds are drawn after local exposure, so their shaders apply its factor to their own colour (the
grid, blur and parameters are bound to them). Bloom's blend onto the main image, like local exposure's, is
done by writing back from a copy.

## Indirect specular (verified in DeferredIndirectSpecular)

- `F0 = mix(0.04, albedo, metalness)`; metalness is `saturate((ColorMetalnessSubsurface.a - 0.502) × 2.0079)`.
- The probe (the sky probe cube) is sampled at the reflected view direction at mip
  `(IBLParameters.y - 1) × (1 - (1 - r)⁴)` (`(1 - r)²` when `ConvolutionType` is 1), with `IBLParameters.y` 10.
  It's blended between the last two probe captures by `IBLParameters.w`.
- It's scaled by `saturate((sky light - IBLSkyFadeParameters.y) / max(.x - .y, 1))³ × IBLParameters.x × .z`.
  With `.z` 0.5, that's the fade in over sky light 12 to 15.
- For metals, when the probe's luminance is below the block-light specular
  (`mix(ambient floor, block light × BlockBaseAmbientLightColorIntensity.w × BlockLightIndirectSpecularIntensity.x,
  saturate(luminance)) × metalness`), the block-light term is used instead.
- Screen-space reflections replace it by `SSR.a × SSRParameters.y`.
- The result is times `F0 × A + B`, with `(A, B)` = BrdfLUT(`saturate(N·V)`, `1 - r`): `renderer/brdf_lut.png`,
  256², with smooth at the bottom row.

The renderer uses the table from the install and the same mip curve. It blurs the analytic sky over a ring of
directions in place of the probe's mips (a cone up to 60° at the roughest mip), and applies the metal
block-light term with the captured `BlockLightIndirectSpecularIntensity` 0.1.

## Local exposure (verified in LocalExposure and BilateralGrid)

Luminance is `dot(rgb, Rec.709)` in Bedrock's units, clamped to `LuminanceRangeParams` (2⁻¹⁰ to 2²⁰), as
log2.

- **BuildBilateralGrid** (compute, atomics). For each pixel, `t = (log2 L + 10) / 30`. The cell is
  `floor(GridDimensions.xy × (p + 0.5) / resolution)`. Slice `s = GridDimensions.z × t`, its index clamped to
  `slices - 2`. It adds `(1 - f, (1 - f) t)` to slice `floor(s)` and `(f, f t)` to the next, in 1/256 fixed point.
- **FilterBilateralGrid**. Within each slice, a Gaussian over the cells within `R = BilateralFilterParams.x`
  (the window clamped to the grid), weight `exp(-d² / (R²/2))`, normalized. There's no blur across slices.
- **GaussianDownsample** (`DownsampledLogLuminance`). The log luminance of the scene, Gaussian-blurred:
  radius `GaussianBlurParams.x`, step `.y`, in scene pixels, same weight.
- **ApplyLocalExposure**. With `A` the log average, `D` the blur and `B = min + range × g.y / g.x` the grid
  sampled trilinearly at (uv, t):
  `base = g.x < 0.001 ? D : mix(B, D, z)` and `out = scene × 2^((A - L) + y (L - base) + x (base - A))`.
  `LocalExposureParams` is (0.75, 1, 0.4), so this is `scene × 2^(0.25 (A - base))`: detail kept, and each
  region's level a quarter of the way to the average.
- `A` is the AverageLuminance texture as LuminanceHistogram's CalculateAverage writes it: the adapted
  histogram average, bounded only by the histogram's log range. The tone mapping's min/max luminance clamp
  is applied to the exposure, not to this. At noon the clamped average is 2 while the histogram's is about 16.
- The average is the histogram's (the brightest 20%), so most of the ground sits below it and is lifted:
  about half a stop on the test scene's grass. That's what the exposure key of 1.46, fitted before this,
  stood in for; the key is now 1 again.
- Order: after lighting, before bloom.

Runtime sizes, from a capture at 1920×1009:
- The grid is built from the scene downsampled to a quarter (480×252, R11G11B10), in whole cells of 32 of
  those pixels: `GridDimensions` (15, 7, 32). It's filtered over `BilateralFilterParams.x` 6 cells into a
  15×7×32 RGBA16F array.
- The blur is `GaussianBlurParams` (12, 1) over the quarter-resolution scene, written at 120×63 (R16F).

The renderer matches those sizes. It gathers the grid in fragment passes from a quarter-resolution log
luminance rather than with atomics, and blurs the logs of those pixels rather than the logs of each colour
tap. It writes the result back from a copy of the main image: a multiply blend (DST_COLOR, ZERO)
onto the main image left it untouched in game, though the same draw unblended landed.

With local exposure applied and no fitted key, the test scene's grass matches Bedrock's screenshots within a
few levels at every time: noon (134,180,90) against (137,173,90), afternoon (113,150,75) against
(126,153,74), low sun (78,103,50) against (79,98,49), midnight (50,62,20) against (55,62,22). Java draws the water
and clouds after this pass, so they aren't locally exposed yet.

## Shadow sampling (verified in the deferred direct-lighting shader)

The engine's PC configuration (`renderer/platform_config/pc/shadow_configuration.pc.json`, ultra) sets:

- 4 cascades of 2048², covering 1/6, 1/3, 1/2 and all of 128 blocks, with depth biases 0.000275 to 0.003 and
  slope biases 1e-5 to 6e-5;
- `light_quantization_steps` 1800 (900 for the outer cascades), so the light moves in 0.2° steps;
- `normal_offset_strength` 0.065;
- `cloud_shadow_contribution` 0.4, with a cloud PCF width of 16 and 7200 quantization steps;
- `max_shadow_distance` 128.

In the shader:

- Each lookup starts from the texel-quantized position, pushed out along the normal by
  `saturate(1 - N·L) × normal offset strength`.
- For each cascade the point falls in, the shader compares against `depth - bias - tan(acos(max(N·L, NdLFloor))) × slope bias`.
- The PCF kernel is `clamp(round(pcf width), 1, 9)`², but **with texel quantization on it is forced to 1**: one
  hard comparison of a single texel. That is what gives Vibrant Visuals its crisp, grid-locked shadow edges.
- The result is the minimum over the cascades, the first-person player shadow and the cloud shadow.
- Cloud shadows are a separate layer of the shadow map (the clouds drawn from the light), compared the same
  way. A shadowed point keeps `max(visibility, 1 - contribution)`.
- Over the last `min(range × 0.1, 8)` blocks of the shadow range, shadows smoothstep out to fully lit.

The renderer matches the single hard tap, the normal offset, the 0.2° steps, the cloud contribution, the four
2048² cascades (1/6, 1/3, 1/2 and all of 128 blocks, the darkest of those a point falls in) and the fade over
the last 8 blocks. The depth biases are in blocks (TUNE: Bedrock's are in its own depth units), and the
volumetric fog reads the outer cascade. Sodium hands each cascade only the chunks the camera sees, so
terrain out of view casts no shadows into it.

## Cloud shadows

Vibrant Visuals' clouds shadow the terrain (`CloudShadowProj`, `CloudShadowsVisible`, and the cloud shadow
toggle in `DirectionalShadowModeAndCloudShadowToggleAndPointLightToggle`). The strength is
`EmissiveMultiplierAndDesaturationAndCloudPCFAndContribution.w`, 0.5 in the material defaults, and `.z` is
the cloud shadow PCF.

The renderer projects each lit point along the light onto the middle of Java's cloud layer. It looks the cell
up in Java's own cloud field, which drifts 0.03 blocks a tick along x, with a 3.96-block z offset. A filled
cell blocks half the direct light.

## Runtime values from GPU captures (PIX, Bedrock 1.26.60, test scene, plains)

Read from the captures' constant buffers at noon, low sun (/time set 11500) and midnight:

- **Time of day is eased**, as in Java: `(2d + 0.5 - cos(πd)/2) / 3`. At tick 11500, SunDir is
  (-0.939024, 0.343851, 0), with the sun 20° up, and the sun's illuminance 38.4944 is the curve's value
  at the eased 0.1941.
- **Sun and moon:** `SunColor`, `MoonColor` and the directional light are the lighting curves' colour as
  sRGB/255 (not linearised) and their illuminance (100, 38.49, 0; the moon 0.4 at midnight).
- **SkyAmbientLightColorIntensity:** (0.473333, 0.655911, 1) at every time, with intensity
  `0.25 × (directional illuminance + 0.02)` (25.005, 9.6286, 0.105).
- **Other lighting values:**
  - `BlockBaseAmbientLightColorIntensity.w` 5.625.
  - `AmbientLightParams` (1, 1, 1, 0.02).
  - `CameraLightIntensity` (0.25, 0.9375).
- **Exposure and local exposure:**
  - `CenterWeight` 25.
  - `LocalExposureParams` (0.75, 1, 0.4).
  - `LuminanceRangeParams` (-10, 20, 2^-10, 2^20).
- **Reflections** (DeferredIndirectSpecular and SSR):
  - `IBLSkyFadeParameters` (15, 12).
  - `BlockLightIndirectSpecularIntensity` 0.1.
  - `SSRParameters` (1, 1).
  - `SSRRoughnessCutoffParams` (0.4, 0.3).
  - `SSRFadingParamsAndThickness` (3, 3, 0.75, 0.025).
  - `SSRRayMarchingParams` (800, 1, 0.2, 40).
  - `SSRTemporalAccumulationParams` (1, 0.5, 2, 0.03125).
- **Local exposure:** `GridDimensions` (15, 7, 32), `BilateralFilterParams` 6, `GaussianBlurParams` (12, 1),
  and the blur written at 120×63, at 1920×1009.
- **Tone map (ColorPostProcessing), the same at noon and midnight:**
  - `ExposureCompensation` (0, 1, 1, 0): constant key 1.
  - `LuminanceMinMaxAndWhitePointAndMinWhitePoint` (0.015625, 2, 94371.84, 1): the average is clamped to
    2⁻⁶ to 2, as `tone_mapping.json`'s -3 and 4 EV give.
  - `TonemapParams0` (5, 1, 1, 0): generic operator.
  - `GenericTonemapperContrastAndScaleAndOffsetAndCrosstalk` (1, 0.997265, 1.3675232, 25) and
    `GenericTonemapperCrosstalkParams` 8. That's the curve solved from mid 0.3 → 0.18 and max 500 → 1, as the
    renderer derives it.
  - The AverageLuminance texture is 16.138 at noon and 0.3771 at midnight, unclamped.
- **Fog and IBL:**
  - `FogAndDistanceControl` (0.75, 4, 384): Vibrant Visuals starts the fog at 0.75, not the files' 0.92.
  - `IBLParameters` (1, 10, 0.5, w).
- **Clouds:**
  - `CloudLightingUniforms` (16, 0.5, 5, 0.0625).
  - Diffuse wrap 0.307.
  - `CloudColor` (1, 1, 1, 0.9) by day and (0.1, 0.1, 0.15, 0.9) at midnight.
  - `DistanceControl` 1152.
- **Volumetric fog (plains):**
  - `AirAlbedoExtinction` (1, 1, 1, 0.001).
  - `HeightFogScaleBias` (-1, 558.38).
  - `HenyeyGreensteinG` (0.75 air, 0.6 water).
  - `MinAmbientValue` 0.
- **Water:**
  - `WaterExtinctionCoefficients` (0.309041, 0.0610688, 0.0411471).
  - `WaterAlbedoExtinction` (0.022877, 0.254416, 0.927091, 0.112349).
- **Bloom:** `BloomParams` (0.02, 0): no threshold.
- **Sun disc:** `SunMoonEmissiveMultiplier` 25 by day, 10 at night.

The renderer now uses these terms directly, in Bedrock's units divided by 100:

- Direct light is `albedo / π × illuminance × N·L`.
- Indirect light is `max(block + sky × s × mix(s⁴, s², camera sky), ambient)`.

That replaces the earlier fitted sky fill, ground bounce and scales. One factor remains fitted: the exposure
key of 1.46.

## Still to analyse

`NdLFloor`, the SSR ray march itself, volumetric fog and local
exposure; the built-in caustics texture; and every *runtime* value, from the game executable.
