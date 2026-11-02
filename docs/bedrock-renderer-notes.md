# Bedrock renderer notes

How Vibrant Visuals (Bedrock 1.26.60) computes things, learned by disassembling the compiled shaders in the
installed game's `data\renderer\materials` (RenderDragon `.material.bin`, format version 26, Direct3D SM6.5
DXIL) for this private project. These notes describe behaviour in our own words; no Mojang code or
disassembly is kept in the repository. Values marked *runtime* are uniforms the engine computes on the CPU,
so the shaders don't contain them; those need measuring from screenshots.

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

## Exposure

The water shading ends by multiplying by `0.18 / PreviousFrameAverageLuminance + 0.0001` when
`PreExposureEnabled`: auto exposure keys the previous frame's average luminance (from `LuminanceHistogram`)
to middle grey 0.18.

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

## Still to analyse

Directional and indirect lighting passes (sky light, ambient, `NdLFloor`, the sky-light heuristic), the SSR
passes, caustics, volumetric fog, the luminance histogram, local exposure, bloom and colour post-processing.
