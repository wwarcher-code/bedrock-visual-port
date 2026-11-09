#version 460 core

// Water variant of Sodium's terrain fragment shader, for the translucent layer in each of Minecraft's OIT
// stages. Glass, ice and other translucent blocks come out exactly as Sodium draws them. Water (its still
// and flowing sprites) is shaded as Bedrock's Vibrant Visuals water: clear water that absorbs light along
// the path to whatever lies beneath (read from the G-buffer's depth), screen-space reflections of the scene
// falling back to the sky, weighted by Fresnel, a sun glint, and light scattered back out of the water.
// Every stage must see the same opacity, so it depends only on geometry, depth and the water settings
// (WaterPass.java).

// MISSING #include <sodium:globals.glsl>
// MISSING #include <sodium:fog.glsl>
// MISSING #include <sodium:chunk_material.glsl>
// MISSING #include <minecraft:oit.glsl>
#ifndef BEDROCKVISUALS_COMMON_GLSL
#define BEDROCKVISUALS_COMMON_GLSL

// Shared by the deferred lighting pass and the water shader: the frame's lighting (LightingPass.java), and
// Bedrock's color grading, tone mapping and gamma, so everything reaches the screen through the same curve.

layout(std140) uniform LightingData {
    mat4 InverseViewProjection; // clip space to camera-relative world space
    vec4 SunDirection;          // towards the sun
    vec4 SunColor;              // linear RGB times illuminance
    vec4 MoonDirection;
    vec4 MoonColor;
    vec4 SkyColor;              // fill light from the sky, before sky-light falloff
    vec4 AmbientColor;          // floor everywhere
    vec4 BlockLightColor;
    mat4 ShadowViewProjection[4]; // per cascade: camera-relative world space to shadow clip space (ShadowPass.java)
    vec4 ShadowParams;          // x: maps valid, y: texel size in UV, z: normal offset in blocks, w: cast by the moon
    vec4 ShadowCascadeBias;     // each cascade's depth bias, in its depth units
    vec4 ShadowCascadeSlope;    // each cascade's slope bias, in its depth units per tan(acos(N·L))
    vec4 DebugParams;           // x: draw the shadow test instead of lit color; y: sky radiance scale for metering;
                                // z: 1 when Bedrock's BRDF table is bound (BrdfLut.java); w: 1 to show local
                                // exposure's factor, 2 only the bloom (GBufferDebugView)
    vec4 SkyHorizonColor;       // sky fill seen towards the horizon; SkyColor is the zenith's
    vec4 CameraGridOffset;      // xyz: the camera's world position modulo 16 (texel grid); w: exposure (AutoExposure)
    vec4 CloudShadowField;      // xy: the camera's position in the cloud field (blocks), z: cloud layer middle above
                                // the camera, w: cloud shadow strength (0 = off) (CloudShadows.java)
    vec4 CloudShadowSize;       // xy: the field's size in cells, z: cell size in blocks, w: 1 when the cells are there
    vec4 FarClouds;             // x: Java's cloud range in blocks, y: Bedrock's cloud distance, z: where the mesh hands
                                // over to the far clouds (horizontal blocks), w: far clouds on
    vec4 UnderwaterParams;      // x: camera under water, y: caustics frame (-1 = off), z: caustics scale, w: power
    vec4 UnderwaterMedium;      // rgb: water extinction per block, w: water surface height above the camera
    vec4 UnderwaterScattering;  // rgb: water scattering per block, w: water phase g
    vec4 VolumeCamera;          // x: the camera's sky access (0 to 1, for the fog's ambient light); yzw: cloud
                                // colour under the current weather, linear (Weather.java)
    vec4 WaterColumnOrigin;     // x, z: the camera's world x and z modulo 512, y: its height above the level's
                                // floor, w: 1 when the water column map is there (WaterColumns.java)
    mat4 ViewProjection;        // camera-relative world space to clip space (InverseViewProjection's inverse)
    mat4 PreviousViewProjection; // the previous frame's, relative to its own camera position
    vec4 CameraMotion;          // xyz: this frame's camera position minus the previous frame's
    vec4 SunMoonParams;         // x: how much of Bedrock's sun and moon shows (0: Java draws them), y: the moon's
                                // phase, z, w: the sun's and moon's emissive multipliers (SunMoon.java)
    vec4 LightVolumeOrigin;     // xyz: the camera's world position modulo the light volume's size; w: 1 when
                                // the volume is filled (LightVolume.java)
};

// Bedrock's water extinction along a view ray from the camera (Underwater.java): the path through water is
// capped where the ray leaves the water through its surface above the camera.
vec3 underwaterTransmittance(vec3 ray, float distance) {
    if (UnderwaterParams.x < 0.5) {
        return vec3(1.0);
    }
    float path = distance;
    if (ray.y > 1e-4) {
        path = min(path, UnderwaterMedium.w / ray.y);
    }
    return exp(-UnderwaterMedium.rgb * path);
}

// Bedrock's color grading and tone mapping for the player's biome (ColorGrading.java).
layout(std140) uniform GradingData {
    vec4 MidContrast, MidGain, MidOffset, MidSaturation;
    vec4 ShadowContrast, ShadowGain, ShadowOffset, ShadowSaturation;
    vec4 HighContrast, HighGain, HighOffset, HighSaturation;
    vec4 Gamma;        // final encoding, after tone mapping
    vec4 TemperatureRow0;  // Bedrock's white balance matrix by rows (w of the first: 1 when on)
    vec4 TemperatureRow1;
    vec4 TemperatureRow2;
    vec4 Bands;        // x: shadows enabled, y: shadowsMax, z: highlights enabled, w: highlightsMin (x average)
    vec4 ToneMapping;  // x: operator (ColorGradingSettings.ToneMapping order), y: average luminance,
                       // z: 1 when the midtones are enabled
};

const float PI = 3.14159265;

vec3 toLinear(vec3 srgb) {
    return pow(srgb, vec3(2.2));
}

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// Bedrock's ColorPostProcessing grading. Each parameter (contrast, saturation, gain, offset) is chosen by the
// pixel's luminance against the scene's average: the highlights' at or above highlightsMin x average, the
// shadows' at or below shadowsMax x average, and between them a straight blend from the shadows' through the
// midtones' (at the average) to the highlights'; a band that is off leaves the midtones' (or, with the
// midtones off too, no change). Then, in order: contrast about the average, saturation about the pixel's
// luminance, gain, and an offset scaled by the average. Bedrock grades before exposing, about the measured
// average; this renderer grades after, about 0.18, where exposure puts that average: the same result, as each
// step scales with it.
vec3 gradeBand(vec3 mid, vec3 shadow, vec3 high, vec3 identity, float luminance, float average) {
    bool shadowsOn = Bands.x > 0.5;
    bool highlightsOn = Bands.z > 0.5;
    float highLimit = Bands.w * average;
    float lowLimit = Bands.y * average;
    if (highlightsOn && luminance >= highLimit) {
        return high;
    }
    bool inShadows = shadowsOn && luminance <= lowLimit;
    if (inShadows || ToneMapping.z < 0.5) {
        return inShadows ? shadow : identity;
    }
    if (shadowsOn && luminance < average) {
        return mix(shadow, mid, (luminance - lowLimit) / (average - lowLimit));
    }
    if (highlightsOn && luminance > average) {
        return mix(mid, high, (luminance - average) / (highLimit - average));
    }
    return mid;
}

vec3 grade(vec3 c) {
    float average = ToneMapping.y;
    // The bands go by the luminance before the white balance, as Bedrock's do.
    float luminance = dot(c, LUMA);
    if (TemperatureRow0.w > 0.5) {
        c = vec3(dot(TemperatureRow0.xyz, c), dot(TemperatureRow1.xyz, c), dot(TemperatureRow2.xyz, c));
    }
    vec3 contrast = gradeBand(MidContrast.rgb, ShadowContrast.rgb, HighContrast.rgb, vec3(1.0), luminance, average);
    c = max(exp2(log2(max(c, 0.0) / average) * contrast) * average, 0.0);
    vec3 saturation = gradeBand(MidSaturation.rgb, ShadowSaturation.rgb, HighSaturation.rgb, vec3(1.0), luminance,
            average);
    float graded = dot(c, LUMA);
    c = max(graded + (c - graded) * saturation, 0.0);
    c = max(c * gradeBand(MidGain.rgb, ShadowGain.rgb, HighGain.rgb, vec3(1.0), luminance, average), 0.0);
    c = max(c + gradeBand(MidOffset.rgb, ShadowOffset.rgb, HighOffset.rgb, vec3(0.0), luminance, average) * average,
            0.0);
    return c;
}

vec3 aces(vec3 c) {
    return clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);
}

vec3 hable(vec3 c) {
    const float A = 0.15, B = 0.50, C = 0.10, D = 0.20, E = 0.02, F = 0.30, W = 11.2;
    vec3 curve = ((c * (A * c + C * B) + D * E) / (c * (A * c + B) + D * F)) - E / F;
    float white = ((W * (A * W + C * B) + D * E) / (W * (A * W + B) + D * F)) - E / F;
    return curve / white;
}

// Bedrock's generic tone mapper (Timothy Lottes' curve, GDC 2016) with the engine's default parameters, from
// Minecraft.Windows.exe: contrast 1, mid-in 0.3 to mid-out 0.18, HDR max 500 to 1, crosstalk 25, crosstalk
// saturation 8. The engine solves b and c so the curve passes through both points; on the brightest channel,
// y = p^a / (p^a b + c), and each channel's ratio to it is pulled towards white as y rises.
const float GENERIC_CONTRAST = 1.0;
const float GENERIC_MID_IN = 0.3;
const float GENERIC_MID_OUT = 0.18;
const float GENERIC_HDR_MAX = 500.0;
const float GENERIC_CROSSTALK = 25.0;
const float GENERIC_CROSSTALK_SATURATION = 8.0;

vec3 genericTonemap(vec3 c) {
    float pm = pow(GENERIC_MID_IN, GENERIC_CONTRAST);
    float ph = pow(GENERIC_HDR_MAX, GENERIC_CONTRAST);
    float b = (ph * GENERIC_MID_OUT - pm) / ((ph - pm) * GENERIC_MID_OUT);
    float cc = ph * (1.0 - b);
    float peak = max(max(c.r, max(c.g, c.b)), 1e-6);
    vec3 ratio = max(c, 0.0) / peak;
    float z = pow(peak, GENERIC_CONTRAST);
    float y = z / (z * b + cc);
    ratio = pow(max(ratio, vec3(1e-9)), vec3(1.0 / GENERIC_CROSSTALK_SATURATION));
    ratio = mix(ratio, vec3(1.0), pow(y, GENERIC_CROSSTALK));
    ratio = pow(ratio, vec3(GENERIC_CROSSTALK_SATURATION));
    return clamp(ratio * y, 0.0, 1.0);
}

// Bedrock's six operators, numbered as ColorGradingSettings.ToneMapping, as its ColorPostProcessing shader
// computes them (Reinhard's extended forms with a white point of 4 until the white point is wired up).
vec3 tonemap(vec3 c) {
    int operator = int(ToneMapping.x + 0.5);
    const float WHITE = 4.0;
    float luminance = dot(c, LUMA);
    if (operator == 0) {
        return c / (1.0 + c);
    }
    if (operator == 1) {
        return c * (1.0 + c / (WHITE * WHITE)) / (1.0 + c);
    }
    if (operator == 2) {
        float mapped = luminance * (1.0 + luminance / (WHITE * WHITE)) / (1.0 + luminance);
        return c * (mapped / max(luminance, 1e-5));
    }
    if (operator == 3) {
        return hable(c);
    }
    if (operator == 4) {
        return aces(c);
    }
    return genericTonemap(c);
}

float ggx(float nDotH, float roughness) {
    float a2 = roughness * roughness * roughness * roughness;
    float d = nDotH * nDotH * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

float smithG1(float nDotX, float roughness) {
    float k = (roughness + 1.0) * (roughness + 1.0) / 8.0;
    return nDotX / (nDotX * (1.0 - k) + k);
}

// Bedrock's texel quantization: the point lighting is evaluated at, snapped to the centre of its 1/16-block
// texel on the face (a camera-relative position and its face normal). Shadows and lighting then come out
// one value per texel, with shadow edges on the texel grid. Faces that aren't axis-aligned (cross-shaped
// plants) are left alone.
const float TEXEL_GRID = 1.0 / 16.0;

vec3 snapToTexel(vec3 position, vec3 n) {
    vec3 a = abs(n);
    if (max(a.x, max(a.y, a.z)) < 0.95) {
        return position;
    }
    vec3 axis = round(n);
    vec3 world = position + CameraGridOffset.xyz;
    vec3 toCentre = TEXEL_GRID * 0.5 - (world - floor(world / TEXEL_GRID) * TEXEL_GRID);
    return position + toCentre - dot(toCentre, axis) * axis;
}

// Bedrock's block light colour by light level (0 to 1): almost white at the source, orange, then red as it
// falls off. Used where a block has no explicit light colour.
vec3 blockLightColor(float level) {
    float l2 = level * level;
    return vec3(l2, l2 * (0.64 + 0.36 * l2), l2 * (0.4 + 0.6 * l2 * l2));
}

// HDR linear color (before exposure) to the screen: exposure, Bedrock's grading, tone map, then its gamma.
vec3 toDisplay(vec3 color) {
    color *= CameraGridOffset.w;
    return pow(clamp(tonemap(grade(color)), 0.0, 1.0), 1.0 / max(Gamma.rgb, vec3(0.1)));
}

#endif
#define SKY_PROBE SkyProbe
#ifndef BEDROCKVISUALS_SKY_GLSL
#define BEDROCKVISUALS_SKY_GLSL

// Bedrock's sky radiance (its SkyProbeDeferredShading and SurfaceRadianceUpscalePass shaders; see
// docs/bedrock-renderer-notes.md, "Atmosphere"): Rayleigh scattering of the sun and moon over a zenith-to-horizon
// colour blend, plus a Mie glow around the sun near the horizon. Vibrant Visuals draws each sky pixel with it.
// The inputs are in Bedrock's units (SkyAtmosphere.java).

layout(std140) uniform AtmosphereData {
    vec4 AtmosphereZenith;      // rgb: zenith colour, linear; w: 1 when Bedrock's sky is drawn
    vec4 AtmosphereHorizon;     // rgb: horizon colour, linear; w: this renderer's units per Bedrock unit
    vec4 AtmosphereBlendStops;  // horizon blend stops: min, start, mie start, max
    vec4 AtmosphereScattering;  // Rayleigh strength, sun Mie strength, moon Mie strength, sun glare shape
    vec4 AtmosphereSun;         // rgb: colour, linear; w: illuminance
    vec4 AtmosphereMoon;
    vec4 AtmosphereFog;         // distance fog: start, 1 / (end - start) (fractions of z), z: distance in blocks, w: on
    vec4 VolumeAir;             // volumetric fog air medium: albedo rgb, extinction per block at full density
    vec4 VolumeHeight;          // density = saturate(x × (y relative to camera) + y); z: phase g; w: on
    vec4 SkyProbeParams;        // x: 1 when the sky probe (SkyProbe.java) is there to read
};

// A direction's place on an octahedral map of the sphere (y up), uv 0 to 1, and back: the sky probe's layout
// (SkyProbe.java), standing in for Bedrock's sky probe cube.

#ifndef BEDROCKVISUALS_OCTAHEDRAL
#define BEDROCKVISUALS_OCTAHEDRAL

vec2 octahedralSigns(vec2 v) {
    return vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
}

vec2 octahedralEncode(vec3 d) {
    d /= abs(d.x) + abs(d.y) + abs(d.z);
    vec2 e = d.xz;
    if (d.y < 0.0) {
        e = (1.0 - abs(d.zx)) * octahedralSigns(d.xz);
    }
    return e * 0.5 + 0.5;
}

vec3 octahedralDecode(vec2 uv) {
    vec2 e = uv * 2.0 - 1.0;
    vec3 d = vec3(e.x, 1.0 - abs(e.x) - abs(e.y), e.y);
    if (d.y < 0.0) {
        d.xz = (1.0 - abs(d.zx)) * octahedralSigns(d.xz);
    }
    return normalize(d);
}

#endif
#ifdef SKY_PROBE
uniform sampler2D SKY_PROBE;    // Bedrock's sky probe: the sky, convolved per roughness into 10 mips (SkyProbe)
#endif

// How much of the sky covers terrain at this distance (Bedrock's FogAndDistanceControl): 0 to 1 between the
// air fog's start and end.
float bedrockFogAmount(float distance) {
    if (AtmosphereFog.w < 0.5) {
        return 0.0;
    }
    return clamp((distance / AtmosphereFog.z - AtmosphereFog.x) * AtmosphereFog.y, 0.0, 1.0);
}

// Henyey-Greenstein phase with g = 0.9, as Bedrock writes it: (1 - g^2) / (1 + g^2 - 2gx)^1.5 / 4π folded into
// the constants and the final 1/4π.
float bedrockMiePhase(float x) {
    float d = 1.81 - 1.8 * x;
    return 0.03610002 / (d * sqrt(d));
}

// Radiance along the unit direction d, in Bedrock's units. sunDir and moonDir point towards the sun and moon.
vec3 bedrockSkyRadiance(vec3 d, vec3 sunDir, vec3 moonDir) {
    float stopMin = AtmosphereBlendStops.x;
    float stopStart = AtmosphereBlendStops.y;
    float stopMie = AtmosphereBlendStops.z;
    float stopMax = AtmosphereBlendStops.w;

    float mieFade = 1.0 - smoothstep(0.0, 1.0, clamp((d.y - stopMin + stopMax) / (stopMie - stopMin), 0.0, 1.0));
    mieFade = mieFade * mieFade * mieFade;
    float horizonLow = stopMin - stopMax;
    float horizonWeight = 1.0 - smoothstep(0.0, 1.0, clamp((d.y - horizonLow) / (stopStart - horizonLow), 0.0, 1.0));
    horizonWeight = horizonWeight * horizonWeight * horizonWeight;
    vec3 base = mix(AtmosphereZenith.rgb, AtmosphereHorizon.rgb, horizonWeight);

    float cosSun = dot(d, sunDir);
    float cosMoon = dot(d, moonDir);
    float rayleigh = 0.75 * AtmosphereScattering.x
            * ((1.0 + cosSun * cosSun) * AtmosphereSun.w + (1.0 + cosMoon * cosMoon) * AtmosphereMoon.w);

    float glare = AtmosphereScattering.w;
    // Bedrock computes pow as exp2(log2(x) * glare): away from the sun that's NaN, which saturate turns to 0.
    float xSun = cosSun > 0.0 ? clamp(pow(cosSun, glare), 0.0, 1.0) : 0.0;
    float xMoon = cosMoon > 0.0 ? clamp(pow(cosMoon, glare), 0.0, 1.0) : 0.0;
    vec3 mie = AtmosphereScattering.y * AtmosphereSun.w * xSun * AtmosphereSun.rgb * bedrockMiePhase(xSun)
            + AtmosphereScattering.z * AtmosphereMoon.w * xMoon * AtmosphereMoon.rgb * bedrockMiePhase(xMoon);

    return (base * rayleigh + mieFade * AtmosphereHorizon.rgb * mie) * 0.07957747;
}

// The sky seen along d, in this renderer's units (before exposure), as a reflection of the given roughness
// sees it: Bedrock's pre-convolved sky probe at mip (IBLParameters.y - 1) × (1 - (1 - r)²) of 9
// (DeferredIndirectSpecular with ConvolutionType 1, as captured), here an octahedral map with the same 10 mips
// (SkyProbe.java). Where a shader has no probe bound, or before the first, a ring of 8 directions around d,
// up to 60° out at the roughest mip, stands in for the convolution.
vec3 bedrockSkyReflection(vec3 d, float roughness, vec3 sunDir, vec3 moonDir) {
    float smoothness = clamp(1.0 - roughness, 0.0, 1.0);
    float mip = 1.0 - smoothness * smoothness;
#ifdef SKY_PROBE
    if (SkyProbeParams.x > 0.5) {
        return textureLod(SKY_PROBE, octahedralEncode(d), mip * 9.0).rgb;
    }
#endif
    vec3 sky = bedrockSkyRadiance(d, sunDir, moonDir);
    if (mip > 0.02) {
        vec3 side = normalize(abs(d.y) < 0.9 ? cross(d, vec3(0.0, 1.0, 0.0)) : cross(d, vec3(1.0, 0.0, 0.0)));
        vec3 up = cross(side, d);
        float angle = mip * 1.0471976;
        float c = cos(angle);
        float s = sin(angle);
        vec3 ring = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.78539816;
            ring += bedrockSkyRadiance(normalize(d * c + (side * cos(a) + up * sin(a)) * s), sunDir, moonDir);
        }
        sky = mix(sky, (sky + ring) / 9.0, clamp(mip * 2.0, 0.0, 1.0));
    }
    return sky * AtmosphereHorizon.w;
}

#endif
#define LOCAL_EXPOSURE_APPLY
#ifndef BEDROCKVISUALS_LOCAL_EXPOSURE_GLSL
#define BEDROCKVISUALS_LOCAL_EXPOSURE_GLSL

// Bedrock's local exposure (LocalExposure, BilateralGrid; see docs/bedrock-renderer-notes.md, "Local exposure").
// Luminance is handled as log2 of Bedrock's units, clamped to LuminanceRangeParams (2^-10 to 2^20).

layout(std140) uniform LocalExposureData {
    vec4 LocalExposureParams; // LocalExposureParams x, y, z; w: log2 of the metered average luminance
    vec4 GridDimensions;      // grid cells across, down, luminance slices; w: grid filter radius in cells
    vec4 BlurParams;          // x: blur radius, y: blur step (quarter-resolution pixels); z: unused; w: Bedrock units per renderer unit
    vec4 ScreenParams;        // xy: the screen in pixels; z: 1 when this frame's grid and blur are ready
};

const float LOG_MIN = -10.0;
const float LOG_MAX = 20.0;

float logLuminance(vec3 color) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722)) * BlurParams.w;
    return log2(clamp(luminance, exp2(LOG_MIN), exp2(LOG_MAX)));
}

#ifdef LOCAL_EXPOSURE_APPLY
uniform sampler2D LocalExposureGrid; // the filtered grid: slices side by side
uniform sampler2D LocalExposureBlur; // the blurred log luminance

vec2 localExposureGridAt(vec2 uv, int slice) {
    vec2 grid = GridDimensions.xy;
    vec2 cell = clamp(uv * grid, vec2(0.5), grid - 0.5);
    return texture(LocalExposureGrid, vec2((float(slice) * grid.x + cell.x) / (grid.x * GridDimensions.z),
            cell.y / grid.y)).rg;
}

// Bedrock's ApplyLocalExposure, in log2 stops for a colour (this renderer's units) at screen position uv. With
// L the log luminance, A the log average, D the blur and B the grid's mean at (uv, L):
//   base = grid empty (weight < 0.001) ? D : mix(B, D, z);  change = (A - L) + y (L - base) + x (base - A)
// gridWeight returns the grid's weight there (for the debug view).
float localExposureChange(vec3 color, vec2 uv, out float gridWeight) {
    gridWeight = 0.0;
    if (ScreenParams.z < 0.5) {
        return 0.0;
    }
    float l = logLuminance(color);
    float d = texture(LocalExposureBlur, uv).r;
    float t = (l - LOG_MIN) / (LOG_MAX - LOG_MIN);
    int slices = int(GridDimensions.z);
    float s = t * float(slices) - 0.5;
    int low = clamp(int(floor(s)), 0, slices - 1);
    int high = clamp(int(floor(s)) + 1, 0, slices - 1);
    vec2 g = mix(localExposureGridAt(uv, low), localExposureGridAt(uv, high), clamp(s - floor(s), 0.0, 1.0));
    gridWeight = g.x;
    float base = d;
    if (g.x >= 0.001) {
        base = mix(LOG_MIN + (LOG_MAX - LOG_MIN) * (g.y / g.x), d, LocalExposureParams.z);
    }
    float a = LocalExposureParams.w;
    return (a - l) + LocalExposureParams.y * (l - base) + LocalExposureParams.x * (base - a);
}

// The colour after local exposure, for shaders drawn after the pass (water, clouds): at this fragment.
vec3 locallyExposed(vec3 color) {
    float weight;
    return color * exp2(localExposureChange(color, gl_FragCoord.xy / max(ScreenParams.xy, vec2(1.0)), weight));
}
#endif

#endif

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_FragDistance;
layout(location = 3) in float fadeFactor;
layout(location = 4) in vec2 v_LightCoord;
layout(location = 5) in vec3 v_Position;

uniform sampler2D u_BlockTex;
uniform sampler2D u_GBufferDepth;  // opaque terrain behind the water, reversed depth, 0 where there is none
uniform sampler2D u_SceneColor;    // the lit opaque scene (sky and terrain), copied before translucency
uniform sampler2D u_LitFlags;      // the lighting pass's HDR scene: alpha 0.5 where it dimmed what's under water
uniform sampler2D u_Reflections;   // this frame's resolved reflections (ReflectionPass): rgb premultiplied by alpha,
                                   // alpha -2 where it found no water (the water marches its own there)

layout(std140) uniform WaterData {
    vec4 StillRect;   // u0, v0, u1, v1 of the water_still sprite in the block atlas
    vec4 FlowRect;    // the same for water_flow
    vec4 Absorption;  // per block, linear RGB
    vec4 Scattering;  // per block, linear RGB; w: 1 when water shading is on
    vec4 Animation;   // x: the surface maps' frame, with its fraction (blended), y: frames in them; z: 1 when u_BrdfLut is Bedrock's;
                      // w: past this horizontal distance, side faces are dropped (0: never)
    vec4 OverlayRect; // the same for water_overlay: water's side faces against see-through blocks (leaves, glass)
};

// Bedrock's split-sum BRDF table (BrdfLut.java), read as DeferredIndirectSpecular reads it.
uniform sampler2D u_BrdfLut;
// The water column map (WaterColumns.java): which surfaces the lighting pass dimmed for the water above them.
uniform sampler2D WaterColumnMap;
#define WATER_COLUMNS WaterColumnMap
// Bedrock's water front and back depths, as this renderer stands in for them with the camera above water: a
// map of each column's top water body around the camera (WaterColumns.java). Included by the lighting pass
// and the water pass, which must agree on what lies under the water: the lighting pass dims what's under it
// by the water above each point, and the water pass dims only what the lighting pass didn't.
// Needs WATER_COLUMNS, the map's sampler, and LightingData (common.glsl).

#ifndef BEDROCKVISUALS_WATER_COLUMNS
#define BEDROCKVISUALS_WATER_COLUMNS

// The map covers 256 columns each way; past this a texel can hold a nearer column.
const float WATER_COLUMN_RANGE = 248.0;

// One column of the map (WaterColumns.java), decoded: the surface's height above the level's floor (0 when
// the column has no water), the lowest water block's, the underside of the first submerged ledge with water
// under it (0 for none), and the sky light (0 to 1) reaching the water above and below that ledge. Heights are
// in the map's frame (WaterColumnOrigin.y is the camera's).
struct WaterColumn {
    float surface;
    float bottom;
    float roof;
    float sky;
    float skyUnder;
    bool loaded;  // Sodium draws Java's chunk (else its water is DH's seabed under the sea: WaterColumns.java)
};

bool waterColumnAt(vec3 p, out WaterColumn c) {
    c = WaterColumn(0.0, 0.0, 0.0, 0.0, 0.0, false);
    if (WaterColumnOrigin.w < 0.5 || max(abs(p.x), abs(p.z)) > WATER_COLUMN_RANGE) {
        return false;
    }
    ivec2 texel = ivec2(mod(floor(p.xz + WaterColumnOrigin.xz), 512.0));
    vec4 column = floor(texelFetch(WATER_COLUMNS, texel, 0) * 65535.0 + 0.5);
    c.surface = column.r / 16.0;
    c.bottom = mod(column.g, 4096.0);
    c.sky = floor(column.g / 4096.0) / 15.0;
    c.roof = mod(column.b, 4096.0);
    c.skyUnder = floor(column.b / 4096.0) / 15.0;
    // 2: a chunk Sodium has just started drawing, held back for a few frames (columnHeld): not yet Java's.
    c.loaded = column.a > 0.5 && column.a < 1.5;
    return c.surface > 0.0;
}

// Whether p's column lies in a chunk Sodium has just started drawing (WaterColumns.java holds it back a few
// frames): its pixels show DH's picture until all that lights the chunk has caught up, so it comes in lit.
bool columnHeld(vec3 p) {
    if (WaterColumnOrigin.w < 0.5 || max(abs(p.x), abs(p.z)) > WATER_COLUMN_RANGE) {
        return false;
    }
    ivec2 texel = ivec2(mod(floor(p.xz + WaterColumnOrigin.xz), 512.0));
    return texelFetch(WATER_COLUMNS, texel, 0).a * 65535.0 > 1.5;
}

// How far below the water's surface the camera-relative point p is (blocks), or -1 when it isn't in the top
// water body of its column (WaterColumns.java). It stands in for Bedrock's water front and back depths with
// the camera above water.
float depthBelowWaterSurface(vec3 p) {
    WaterColumn c;
    if (!waterColumnAt(p, c)) {
        return -1.0;
    }
    float y = p.y + WaterColumnOrigin.y;
    // Positions come from the view (with view bobbing's sway, up to about 0.1 blocks) and the height from the
    // camera without it, so floors lying exactly on the body's bottom get some slack: without it they flickered
    // in and out of the water while walking.
    return y >= c.bottom - 0.3 && y < c.surface ? max(c.surface - y, 0.0) : -1.0;
}

// The sky light (0 to 1) reaching the water at p (WaterColumns.java): full over open water, less under a ledge
// or a tree, by Minecraft's sky light beside it; under a submerged ledge, the sky light of the water beneath
// it. -1 outside the map or where it has no water.
float waterColumnSkyLight(vec3 p) {
    WaterColumn c;
    if (!waterColumnAt(p, c)) {
        return -1.0;
    }
    return c.roof > 0.0 && p.y + WaterColumnOrigin.y < c.roof ? c.skyUnder : c.sky;
}

// How much of the sky ambient light reaches water with sky light s (0 to 1). Bedrock lights its volume's
// ambient by per-froxel sky samples (PopulateVolume: SkyAmbientLightColorIntensity × the s_SkyAmbientSamples
// texture, or CameraAmbientContribution where those are off); this stands in with Minecraft's sky light,
// through the same curve as the terrain's sky ambient (lighting/deferred.fsh), so water under a ledge darkens
// as the ledge's floor does. 1 over open water, as before.
float waterSkyAccess(float s) {
    float cameraSky = sqrt(VolumeCamera.x) * 15.0 / 16.0;
    return s * mix(pow(s, 4.0), s * s, cameraSky);
}

// The point a visible surface's water test is made at: nudged back towards the camera, into the water in
// front of it (its face lies exactly on a block boundary, where the column or the body's bottom could go either
// way). Both passes use it, so they agree.
vec3 waterTestPoint(vec3 p) {
    return p - normalize(p) * 0.05;
}

// The point a lit surface's water test is made at, with its normal n: a quarter block out from its face along
// the face's axis (blocks' faces are axis-aligned; the snap ignores normal maps), into the water in front of
// it. A quarter block is more than view bobbing's offset between how this renderer's passes reconstruct a
// point (up to about a tenth of a block), which with a smaller nudge put a wall's points in the wall's own
// column at times (no water there) and left bright edges along underwater walls while walking.
vec3 waterTestPointOn(vec3 p, vec3 n) {
    vec3 a = abs(n);
    vec3 face = a.x > a.y && a.x > a.z ? vec3(sign(n.x), 0.0, 0.0)
            : a.y > a.z ? vec3(0.0, sign(n.y), 0.0) : vec3(0.0, 0.0, sign(n.z));
    return waterTestPoint(p) + face * 0.25;
}

// Bedrock's water volume (PopulateVolume's water medium, WaterAlbedoExtinction.w in the captures of the default
// water): 0.112 a block in every channel. Seen from above water, what lies under the surface reaches the camera
// through the scattering volume's transmittance (which holds this) and then the surface's WaterExtinction
// (DeferredWater), so both the floor and the light the water scatters fade at their sum: deep water settles at
// about the albedo times the light within a few blocks, and a shallow floor beside it is dimmed as much as
// Bedrock's (with the view extinction alone the blue built for some 25 blocks to several times Bedrock's level,
// and a drop-off showed as a hard seam).
const float WATER_VOLUME_EXTINCTION = 0.112349;

vec3 waterScatterExtinction() {
    return UnderwaterMedium.rgb + vec3(WATER_VOLUME_EXTINCTION);
}

// What's left of light the water scatters at p on its way out through the surface, along the view.
vec3 waterScatterTransmittanceTo(vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(waterTestPoint(p));
    if (depth < 0.0) {
        return vec3(1.0);
    }
    return exp(-waterScatterExtinction() * min(depth / max(-dir.y, 0.05), 64.0));
}

// The view's extinction alone (WaterExtinction) over the water above the point at p along the view: what
// DeferredWater applies to what lies under the surface, the volume's own 0.112 a block being the fog grid's
// (FroxelVolume) to apply as it integrates.
vec3 waterViewTransmittanceTo(vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(waterTestPoint(p));
    if (depth < 0.0) {
        return vec3(1.0);
    }
    return exp(-UnderwaterMedium.rgb * min(depth / max(-dir.y, 0.05), 64.0));
}

// As waterViewTransmittanceTo for a lit surface's chosen test point (waterTestPointOn) off the point p.
vec3 waterViewTransmittanceAt(vec3 testPoint, vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(testPoint);
    if (depth < 0.0) {
        return vec3(1.0);
    }
    depth = max(depth + testPoint.y - p.y, 0.0);
    return exp(-UnderwaterMedium.rgb * min(depth / max(-dir.y, 0.05), 64.0));
}

// As waterTransmittanceTo, for a test point already chosen (waterTestPointOn) off the surface point p: the
// test point says whether p is under the water, p how deep.
vec3 waterTransmittanceAt(vec3 testPoint, vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(testPoint);
    if (depth < 0.0) {
        return vec3(1.0);
    }
    depth = max(depth + testPoint.y - p.y, 0.0);
    float path = min(depth / max(-dir.y, 0.05), 64.0);
    return exp(-waterScatterExtinction() * path);
}

// The water's extinction (the view's and the volume's) between its surface and the surface at p, along the view from the camera (above
// water), or 1 when p isn't under the mapped water.
vec3 waterTransmittanceTo(vec3 p, vec3 dir) {
    return waterTransmittanceAt(waterTestPoint(p), p, dir);
}

// The light the water scatters towards the camera over `path` blocks of it below the surface, along the view
// direction dir: what the lighting pass's march adds within the map (sky ambient over 4π and the sun or moon
// through the water's phase function, dimmed by the water in front of each point along the view), in closed
// form for a uniform layer, for water the map doesn't cover (farther out, and Distant Horizons' water). The
// sun's share is scaled by sunVisibility (whether what's beneath is in shade), the sky's by skyAccess (the
// camera's VolumeCamera.x, or waterSkyAccess where the water's sky light is known). This renderer's units,
// before exposure.
vec3 waterInScatter(vec3 dir, float path, float sunVisibility, float skyAccess) {
    bool moon = ShadowParams.w > 0.5;
    vec3 l = normalize(moon ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moon ? MoonColor.rgb : SunColor.rgb;
    float g = UnderwaterScattering.w;
    float cosine = dot(l, -dir);
    float phase = (1.0 - g * g) * 0.07957747 / pow(max(1.0 + g * (2.0 * cosine + g), 1e-4), 1.5);
    vec3 ambient = SkyColor.rgb * skyAccess * 0.07957747;
    vec3 sigma = waterScatterExtinction();
    return UnderwaterScattering.rgb * (ambient + light * phase * sunVisibility) * (1.0 - exp(-sigma * path)) / sigma;
}

#endif
// The outer shadow cascade and the cloud cells, for the volumetric fog in front of the surface.
uniform sampler2D DistantDepth;     // Distant Horizons' ground this frame (LightingPass.java), when it is lit
layout(std140) uniform DistantData {
    mat4 DistantInverseProjection; // Distant Horizons' clip space to its view space
    vec4 DistantParams;            // x: its terrain is lit here, y: reversed depth, z: [0, 1] clip depth
    vec4 DistantLightmap;
};
uniform sampler2D u_ShadowMap3;
uniform sampler2D u_CloudShadowMap;

// Bedrock's water_still_grey normal map and MERS (WaterSurfaceMaps.java): strips of 16x16 frames.
uniform sampler2D u_WaterNormal;
uniform sampler2D u_WaterMers;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Water's reflectance looking straight down; about 2% for an air-water surface.
const float WATER_F0 = 0.02;
// Light path assumed where nothing opaque lies behind the water (open sea, beyond the terrain).
const float BOTTOMLESS = 64.0;

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
        color = sampleColorForAccumulation(color);
        vec4 fogColor = vec4(u_FogColor.rgb * color.a, u_FogColor.a);
    #else
        vec4 fogColor = u_FogColor;
    #endif

    #ifdef OIT_ALPHA_ONLY
    float factor = 1.0;
    #else
    float factor = fadeFactor;
    #endif

    return _linearFog(color, v_FragDistance, fogColor, u_EnvironmentFog, u_RenderFog, factor);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 texelScreenSize) {
    vec2 uvTexelCoords = uv / pixelSize;
    vec2 texelCenter = round(uvTexelCoords) - 0.5f;
    vec2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (texelOffset - 0.5f) * pixelSize / texelScreenSize + 0.5f;
    texelOffset = clamp(texelOffset, 0.0f, 1.0f);
    uv = (texelCenter + texelOffset) * pixelSize;
    return textureGrad(source, uv, du, dv);
}

vec4 sampleNearest(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    return sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
}

vec4 sampleRGSS(sampler2D source, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, maxTexelSize);
    float effectiveDerivative = sqrt(min(length(du), length(dv)) * max(length(du), length(dv)));
    float mipLevelExact = max(0.0, log2(effectiveDerivative / minPixelSize));
    const vec2 offsets[4] = vec2[](vec2(0.125, 0.375), vec2(-0.125, -0.375), vec2(0.375, -0.125), vec2(-0.375, 0.125));
    vec4 rgssColor = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        rgssColor += textureLod(source, uv + offsets[i] * pixelSize, mipLevelExact);
    }
    rgssColor *= 0.25;
    vec4 nearestColor = sampleNearest(source, uv, pixelSize, du, dv, texelScreenSize);
    return mix(nearestColor, rgssColor, blendFactor);
}

bool inRect(vec2 uv, vec4 rect) {
    return all(greaterThanEqual(uv, rect.xy)) && all(lessThanEqual(uv, rect.zw));
}

bool isWater() {
    return Scattering.w > 0.5 && (inRect(v_TexCoord, StillRect) || inRect(v_TexCoord, FlowRect)
            || inRect(v_TexCoord, OverlayRect));
}

// Distance from the camera along the view axis, from a depth value. With w = -z, depth = (P22 z + P32) / -z,
// so -z = P32 / (depth + P22); this holds for reversed and infinite projections alike.
float viewDistance(float depth) {
    return u_ProjectionMatrix[3][2] / (depth + u_ProjectionMatrix[2][2]);
}

struct WaterSurface {
    vec3 n;              // with the surface map's per-texel tilt
    float roughness;     // the surface map's per-texel roughness
    float metalness;     // the surface map's per-texel metalness, as the G-buffer stores it
    vec3 v;
    float fresnel;
    vec3 transmittance;  // along the path from the surface to the bottom
    float path;          // blocks of water from the surface to the bottom along the view
    float beneathSky;    // the sky light of the water over what's beneath (water_columns.glsl), or -1 if unmapped
    float beneathLit;    // 1 where what's beneath is in sunlight (the outer cascade), 0 in shade
    bool scatters;       // the water's in-scattered light is this pass's to add (the lighting pass's map missed it)
    float opacity;       // what the OIT stages blend with; the same in every stage
    bool flagged;        // the lighting pass dimmed what's beneath (its HDR alpha says so)
    bool bottomless;     // nothing drawn beneath it, neither Java's terrain nor Distant Horizons'
};

// How many of the surface map's texels one pixel spans (set in main from the UV derivatives).
float surfaceFootprint = 1.0;

// This texel's normal and MERS from Bedrock's surface maps at the animation's time: between the flipbook's
// two frames around it, blended. Stepped a whole frame every 2 ticks, every texel's normal turned at once, and
// the mirror rays of the water near a shore jumped between the shore and the sky beyond it ten times a second
// (the flicker test: the reflections were steady with the ripples held still and popped as they moved).
// Bedrock's surface moves continuously. The water sprite's UVs cover 16 texels per block (32 for the flowing
// sprite, which spans two).
void surfaceTexelOf(int frame, out vec3 tangentNormal, out vec4 mers);

void surfaceTexel(out vec3 tangentNormal, out float roughness, out float metalness) {
    float frames = max(Animation.y, 1.0);
    float time = mod(Animation.x, frames);
    int f0 = int(floor(time));
    int f1 = int(mod(float(f0 + 1), frames));
    vec3 n0;
    vec3 n1;
    vec4 m0;
    vec4 m1;
    surfaceTexelOf(f0, n0, m0);
    surfaceTexelOf(f1, n1, m1);
    float t = time - float(f0);
    tangentNormal = normalize(mix(n0, n1, t));
    vec4 mers = mix(m0, m1, t);
    roughness = mers.b;
    // Bedrock's G-buffer keeps metalness in the colour target's alpha as 0.5 + 0.498 m, which
    // DeferredIndirectSpecular reads back as saturate((a - 0.502) × 2.0079): water's 0.0706 becomes 0.0667.
    metalness = clamp((0.5 + 0.498 * mers.r - 0.50196) * 2.00787, 0.0, 1.0);
}

void surfaceTexelOf(int frame, out vec3 tangentNormal, out vec4 mers) {
    vec4 rect = inRect(v_TexCoord, StillRect) ? StillRect : inRect(v_TexCoord, FlowRect) ? FlowRect : OverlayRect;
    vec2 local = (v_TexCoord - rect.xy) / max(rect.zw - rect.xy, vec2(1e-6));
    float spriteTexels = max(round((rect.z - rect.x) / u_TexelSize.x), 1.0);
    ivec2 mapSize = textureSize(u_WaterNormal, 0);
    int frameSize = max(mapSize.x, 1);
    ivec2 texel = ivec2(mod(floor(local * spriteTexels), float(frameSize)));
    texel.y += frame * frameSize;
    texel = clamp(texel, ivec2(0), mapSize - 1);
    // The mip level for the texels this pixel spans (the maps carry a chain down to a texel per frame), read
    // nearest within it as the base level is: far off, the ripples average out as Bedrock's do.
    // Between the two levels around it, blended, as trilinear sampling would: a whole level per pixel stepped
    // with distance and striped distant water in bands of more and less ripple.
    float level = clamp(log2(max(surfaceFootprint, 1.0)), 0.0, log2(float(frameSize)));
    int lod = int(floor(level));
    int lodUp = min(lod + 1, int(log2(float(frameSize))));
    float blend = level - float(lod);
    ivec2 near = texel >> lod;
    ivec2 far = texel >> lodUp;
    vec3 n0 = texelFetch(u_WaterNormal, near, lod).xyz * 2.0 - 1.0;
    vec3 n1 = texelFetch(u_WaterNormal, far, lodUp).xyz * 2.0 - 1.0;
    tangentNormal = mix(n0, n1, blend);
    mers = mix(texelFetch(u_WaterMers, near, lod), texelFetch(u_WaterMers, far, lodUp), blend);
}

// Defined with the surface fog below: the outer cascade's shadow test at p.
float surfaceFogShadow(vec3 p);
// Defined with the reflections below: Distant Horizons' ground at a texture and a clip position.
float distantDistanceAt(vec2 uv, vec2 ndcXy);

WaterSurface waterSurface(vec3 faceNormal, vec3 tangent, vec3 bitangent) {
    WaterSurface s;
    vec3 face = faceNormal;
    if (dot(face, v_Position) > 0.0) {
        face = -face;
    }
    vec3 tangentNormal;
    surfaceTexel(tangentNormal, s.roughness, s.metalness);
    s.n = normalize(tangent * tangentNormal.x + bitangent * tangentNormal.y + face * tangentNormal.z);
    if (dot(s.n, v_Position) > 0.0) {
        s.n = face;
    }
    s.v = normalize(-v_Position);
    float nDotV = max(dot(s.n, s.v), 1e-4);
    s.fresnel = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - nDotV, 5.0);

    float opaque = texelFetch(u_GBufferDepth, ivec2(gl_FragCoord.xy), 0).r;
    float path = BOTTOMLESS;
    s.bottomless = opaque <= 0.0;
    if (opaque > 0.0) {
        // The bottom lies on the same view ray, this much farther along the view axis.
        float ratio = viewDistance(opaque) / max(viewDistance(gl_FragCoord.z), 1e-4);
        path = min(length(v_Position) * max(ratio - 1.0, 0.0), BOTTOMLESS);
    } else if (DistantParams.x > 0.5) {
        // Nothing of Java's under this water: near the edge of the loaded chunks the view runs on under the
        // surface past them, to Distant Horizons' ground. Counted as bottomless, that water came out deep
        // blue: a wall hanging under the chunk border, in front of DH's water.
        vec4 clip = u_ProjectionMatrix * u_ModelViewMatrix * vec4(v_Position, 1.0);
        float ground = distantDistanceAt(gl_FragCoord.xy / vec2(textureSize(u_GBufferDepth, 0)), clip.xy / clip.w);
        if (ground > 0.0) {
            s.bottomless = false;
            path = min(max(ground - length(v_Position), 0.0), BOTTOMLESS);
        }
    }
    // The view's extinction and the water volume's (water_columns.glsl).
    s.transmittance = exp(-(Absorption.rgb + Scattering.rgb + vec3(WATER_VOLUME_EXTINCTION)) * path);
    s.path = path;
    s.scatters = UnderwaterParams.x < 0.5;
    s.beneathLit = 1.0;
    s.beneathSky = -1.0;
    // Where the water column map puts what's beneath under this water, the lighting pass already dimmed it by
    // the water above it (and the water's in-scattered light likewise) and says so, so nothing more here:
    // exactly one of them applies it. Whatever the path's length: gated on the path being under the 64-block
    // cap, long paths (looking across water far out, or down into a hollow under a ledge) skipped the lighting
    // pass's flag and got this pass's deep-water fallback on top, saturated blue.
    if (opaque > 0.0) {
        vec3 beneath = v_Position * (viewDistance(opaque) / max(viewDistance(gl_FragCoord.z), 1e-4));
        s.beneathLit = surfaceFogShadow(beneath);
        s.beneathSky = waterColumnSkyLight(waterTestPoint(beneath));
    }
    // Its own flag rather than the same test here: this pass reconstructs the point from the water's geometry
    // and the lighting pass from its matrices, and with view bobbing they differ enough to disagree at a
    // block's edge. Read over Distant Horizons' ground too, which the lighting pass dims and flags the same
    // way (read only over Java's terrain, DH's ground seen through the near water was dimmed by both passes).
    float flag = texelFetch(u_LitFlags, ivec2(gl_FragCoord.xy), 0).a;
    s.flagged = flag > 0.25 && flag < 0.75;
    if (s.flagged) {
        s.transmittance = vec3(1.0);
        s.scatters = false;
    }
    if (UnderwaterParams.x > 0.5) {
        // Seen from under water (DeferredWater's underwater branch): what lies beyond is in air, and the
        // lighting pass already attenuated it along the path to this surface. Outside Snell's window
        // (1.33² (1 - cos²) > 1) the surface reflects totally.
        s.transmittance = vec3(1.0);
        float cosine = clamp(dot(s.n, s.v), 0.0, 1.0);
        if (1.7689 * (1.0 - cosine * cosine) > 1.0) {
            s.fresnel = 1.0;
        }
    }
    // OIT blends one opacity for all channels. Using the least-transmitted channel keeps the color solved
    // for below (waterColor) non-negative; the per-channel tint goes into that color.
    float leastTransmitted = min(s.transmittance.r, min(s.transmittance.g, s.transmittance.b));
    s.opacity = clamp(1.0 - (1.0 - s.fresnel) * leastTransmitted, 0.0, 1.0);
    // Seen from above, the water adds its reflection to what lies beneath in HDR and tone maps the sum (waterColor),
    // as Bedrock's deferred water does and the lighting pass does for DH's water. That needn't fit the one
    // opacity the blend allows over the scene: drawn opaque, with what lies beneath taken from the scene copies.
    // (Mixed in encoded colours, the near water's reflections came out much weaker than DH's water beside it.)
    if (UnderwaterParams.x < 0.5) {
        s.opacity = 1.0;
    }
    // The water views that leave the surface out (GBufferDebugView 3 and 4): fully see-through.
    // And the far terrain views (7 to 10), which show only what's under it.
    // And the water map view (the shadow test's DebugParams.x 3), whose codes the surface's own shading
    // covered in a mess of streaks.
    if (abs(DebugParams.w - 3.0) < 0.5 || abs(DebugParams.w - 4.0) < 0.5
            || (DebugParams.w > 6.5 && DebugParams.w < 10.5) || DebugParams.x > 2.5) {
        s.opacity = 0.0;
    }
    // The water flag view (6): see-through to the lighting pass's codes, but yellow where this pass shades
    // the water itself with nothing at all beneath it (its deep-water fallback).
    if (abs(DebugParams.w - 6.0) < 0.5) {
        s.opacity = !s.flagged && s.bottomless && UnderwaterParams.x < 0.5 ? 1.0 : 0.0;
    }
    // The reflection views (11 to 13): the reflection alone, opaque.
    if (DebugParams.w > 10.5 && DebugParams.w < 13.5 && UnderwaterParams.x < 0.5) {
        s.opacity = 1.0;
    }
    return s;
}

// Screen-space reflection: march the mirror ray through the G-buffer's depth. Returns the scene's color at the
// hit in rgb and a confidence in a (0 where the ray found nothing or left the screen).
const int SSR_STEPS = 32;
const int SSR_REFINE = 5;
const float SSR_MAX_DISTANCE = 96.0;

// Pixel position of a camera-relative point, in gl_FragCoord's orientation; z is the distance along the view
// axis.
vec3 toScreen(vec3 position, vec2 size, bool flipY) {
    vec4 clip = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);
    vec2 pixel = (clip.xy / clip.w * 0.5 + 0.5) * size;
    if (flipY) {
        pixel.y = size.y - pixel.y;
    }
    return vec3(pixel, clip.w);
}

// Whether the framebuffer's y runs against clip space's: set in main from how this fragment's clip-space
// height changes down the screen. It was decided per pixel by which way put the fragment where it is, which on
// the screen's middle row both did: that row's reflections were looked up upside down (a line across the water).
bool screenFlipped = false;

bool onScreen(vec2 pixel, vec2 size) {
    return all(greaterThanEqual(pixel, vec2(0.0))) && all(lessThan(pixel, size));
}

// How far a reflection found at screen position hit is trusted near the top and bottom of the screen, where the
// scene it reflects runs out (sideways, the marches hold to the edge: toScreenEdge).
float screenEdgeFade(vec2 hit, vec2 size) {
    vec2 edge = min(hit, size - hit) / size;
    return smoothstep(0.0, 0.08, edge.y);
}

// A point's screen position for the reflection marches, held to the screen's left and right edges: a mirror
// ray that leaves the screen sideways goes on up the edge column (on water, what it reflects lies almost
// straight above), rather than missing and showing the sky where the water meets the screen's edge.
vec3 toScreenEdge(vec3 position, vec2 size, bool flipY) {
    vec3 screen = toScreen(position, size, flipY);
    screen.x = clamp(screen.x, 0.5, size.x - 0.5);
    return screen;
}

bool onScreenY(vec2 pixel, vec2 size) {
    return pixel.y >= 0.0 && pixel.y < size.y;
}

vec4 screenSpaceReflection(vec3 origin, vec3 direction, vec2 size, bool flipY) {
    float stepLength = 0.25;
    float travelled = 0.0;
    vec3 previous = origin;
    for (int i = 0; i < SSR_STEPS && travelled < SSR_MAX_DISTANCE; i++) {
        travelled += stepLength;
        vec3 point = origin + direction * travelled;
        vec3 screen = toScreenEdge(point, size, flipY);
        if (screen.z <= 0.0 || !onScreenY(screen.xy, size)) {
            return vec4(0.0);
        }
        float depth = texelFetch(u_GBufferDepth, ivec2(screen.xy), 0).r;
        if (depth > 0.0 && screen.z > viewDistance(depth)) {
            // Behind the depth buffer: the crossing is refined between the last step in front and this one, and
            // is a hit if the refined point lies on the surface; else the ray passed behind a thin object and
            // goes on. (Given up at the first object it passed behind, the reflections of what lay beyond it
            // were cut off: the sky showed instead.)
            vec3 front = previous;
            vec3 back = point;
            for (int j = 0; j < SSR_REFINE; j++) {
                vec3 middle = (front + back) * 0.5;
                vec3 m = toScreenEdge(middle, size, flipY);
                float d = onScreenY(m.xy, size) ? texelFetch(u_GBufferDepth, ivec2(m.xy), 0).r : 0.0;
                if (d > 0.0 && m.z > viewDistance(d)) {
                    back = middle;
                } else {
                    front = middle;
                }
            }
            vec3 b = toScreenEdge(back, size, flipY);
            float bDepth = onScreenY(b.xy, size) ? texelFetch(u_GBufferDepth, ivec2(b.xy), 0).r : 0.0;
            if (bDepth > 0.0 && b.z - viewDistance(bDepth) < max(length(back - front) * 4.0, 0.5)) {
                vec2 hit = clamp(b.xy, vec2(0.0), size - 1.0);
                // Fade towards the screen's edges, where the reflected scene runs out.
                return vec4(texelFetch(u_LitFlags, ivec2(hit), 0).rgb, screenEdgeFade(hit, size));
            }
        }
        previous = point;
        stepLength *= 1.18;
    }
    return vec4(0.0);
}

// Distance to Distant Horizons' terrain: its depth read at texture position uv (the G-buffer's orientation),
// unprojected at the clip position ndcXy (which runs the other way vertically when the framebuffer is
// flipped: unprojecting at the texture position mirrored the far terrain, and the reflections found sky
// where they should have found the far shore). -1 where DH drew nothing.
float distantDistanceAt(vec2 uv, vec2 ndcXy) {
    float d = texture(DistantDepth, uv).r;
    bool cleared = DistantParams.y > 0.5 ? d <= 0.0 : d >= 1.0;
    if (cleared) {
        return -1.0;
    }
    float z = DistantParams.z > 0.5 ? d : d * 2.0 - 1.0;
    vec4 view = DistantInverseProjection * vec4(ndcXy, z, 1.0);
    return length(view.xyz / view.w);
}

// Where the near march found nothing: the mirror ray on out against Java's depth and Distant Horizons' (whose
// terrain the lighting pass lit into the scene copy), in steps that grow with the distance, so water near the
// edge of the render distance reflects the far shore rather than the sky behind it.
const int DISTANT_SSR_STEPS = 40;
bool distantSceneAt(vec3 p, vec2 size, bool flipY, out float sceneDistance, out float rayDistance, out ivec2 pixel);

vec4 distantReflection(vec3 origin, vec3 direction, vec2 size, bool flipY) {
    if (DistantParams.x < 0.5) {
        return vec4(0.0);
    }
    float stepLength = max(length(origin) * 0.01, 1.0);
    float travelled = 0.0;
    vec3 previous = origin;
    for (int i = 0; i < DISTANT_SSR_STEPS; i++) {
        travelled += stepLength;
        stepLength *= 1.15;
        vec3 point = origin + direction * travelled;
        vec3 screen = toScreenEdge(point, size, flipY);
        if (screen.z <= 0.0 || !onScreenY(screen.xy, size)) {
            return vec4(0.0);
        }
        ivec2 pixel = ivec2(screen.xy);
        float depth = texelFetch(u_GBufferDepth, pixel, 0).r;
        float sceneDistance;
        float rayDistance;
        if (depth > 0.0) {
            sceneDistance = viewDistance(depth);
            rayDistance = screen.z;
        } else {
            vec2 uv = (vec2(pixel) + 0.5) / size;
            vec2 ndcXy = vec2(uv.x, flipY ? 1.0 - uv.y : uv.y) * 2.0 - 1.0;
            sceneDistance = distantDistanceAt(uv, ndcXy);
            rayDistance = length(point);
        }
        if (sceneDistance < 0.0 || rayDistance <= sceneDistance) {
            previous = point;
            continue;
        }
        // Behind the scene: the crossing is refined between the last step in front and this one, and is a hit if
        // the refined point lies on the surface; else the ray passed behind something and goes on. (Taken only
        // within two steps of the surface, rays grazing up a far mountain's face, whose steps there are tens of
        // blocks long, jumped past it and found the sky: its reflection was cut off.)
        vec3 front = previous;
        vec3 back = point;
        for (int j = 0; j < 6; j++) {
            vec3 middle = (front + back) * 0.5;
            float sceneHere;
            float rayHere;
            ivec2 at;
            if (distantSceneAt(middle, size, flipY, sceneHere, rayHere, at) && rayHere > sceneHere) {
                back = middle;
            } else {
                front = middle;
            }
        }
        float sceneBack;
        float rayBack;
        ivec2 hitPixel;
        if (distantSceneAt(back, size, flipY, sceneBack, rayBack, hitPixel)
                && rayBack - sceneBack < max(length(back - front) * 4.0, 1.0)) {
            return vec4(texelFetch(u_LitFlags, hitPixel, 0).rgb, screenEdgeFade(vec2(hitPixel), size));
        }
        previous = point;
    }
    return vec4(0.0);
}

// The scene's distance at camera-relative point p's pixel, and p's own in the same terms (along the view axis
// against Java's depth, from the camera against DH's); false off the screen or where neither drew anything.
bool distantSceneAt(vec3 p, vec2 size, bool flipY, out float sceneDistance, out float rayDistance, out ivec2 pixel) {
    vec3 screen = toScreenEdge(p, size, flipY);
    pixel = ivec2(screen.xy);
    sceneDistance = -1.0;
    rayDistance = 0.0;
    if (screen.z <= 0.0 || !onScreenY(screen.xy, size)) {
        return false;
    }
    float depth = texelFetch(u_GBufferDepth, pixel, 0).r;
    if (depth > 0.0) {
        sceneDistance = viewDistance(depth);
        rayDistance = screen.z;
    } else {
        vec2 uv = (vec2(pixel) + 0.5) / size;
        vec2 ndcXy = vec2(uv.x, flipY ? 1.0 - uv.y : uv.y) * 2.0 - 1.0;
        sceneDistance = distantDistanceAt(uv, ndcXy);
        rayDistance = length(p);
    }
    return sceneDistance >= 0.0;
}

// Bedrock's volumetric fog in front of the surface (DeferredWater applies the scattering volume on top: in-scatter
// times its alpha plus transmittance times its colour). The lighting pass's march (lighting/deferred.fsh,
// volumetricFog), in fewer steps: from above water the path to the surface is all air.
const int SURFACE_FOG_STEPS = 16;

float surfaceFogShadow(vec3 p) {
    if (ShadowParams.x < 0.5) {
        return 1.0;
    }
    vec4 clip = ShadowViewProjection[3] * vec4(p, 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return 1.0;
    }
    return clip.z >= texture(u_ShadowMap3, uv).r - ShadowCascadeBias[3] ? 1.0 : 0.0;
}

float surfaceFogCloudShadow(vec3 p, vec3 towardsLight) {
    if (CloudShadowField.w <= 0.0 || towardsLight.y <= 0.01) {
        return 1.0;
    }
    float rise = CloudShadowField.z - p.y;
    if (rise <= 0.0) {
        return 1.0;
    }
    vec2 field = (CloudShadowField.xy + p.xz + towardsLight.xz * (rise / towardsLight.y)) / CloudShadowSize.z;
    float filled = texture(u_CloudShadowMap, (floor(field) + 0.5) / CloudShadowSize.xy).r;
    return filled > 0.5 ? 1.0 - CloudShadowField.w : 1.0;
}

// rgb: in-scattered light (before exposure); transmittance in the out parameter.
vec3 surfaceFog(vec3 dir, float distance, out vec3 transmittance) {
    transmittance = vec3(1.0);
    if (AtmosphereZenith.w < 0.5 || VolumeHeight.w < 0.5) {
        return vec3(0.0);
    }
    float stepLength = min(distance, 250.0) / float(SURFACE_FOG_STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    bool moonCasts = ShadowParams.w > 0.5;
    vec3 l = normalize(moonCasts ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moonCasts ? MoonColor.rgb : SunColor.rgb;
    float g = VolumeHeight.z;
    float cosine = dot(l, -dir);
    float phase = (1.0 - g * g) * 0.07957747 / pow(max(1.0 + g * (2.0 * cosine + g), 1e-4), 1.5);
    vec3 ambient = SkyColor.rgb * VolumeCamera.x * 0.07957747;
    vec3 inScattered = vec3(0.0);
    for (int i = 0; i < SURFACE_FOG_STEPS; i++) {
        float t = (float(i) + jitter) * stepLength;
        vec3 p = dir * t;
        float density = clamp(VolumeHeight.x * p.y + VolumeHeight.y, 0.0, 1.0);
        vec3 extinction = vec3(max(VolumeAir.w * density, 1e-6));
        vec3 scattering = extinction * VolumeAir.rgb * (1.0 - bedrockFogAmount(t));
        float visibility = surfaceFogShadow(p) * surfaceFogCloudShadow(p, l);
        vec3 source = scattering * (ambient + light * phase * visibility);
        vec3 stepTransmittance = exp(-extinction * stepLength);
        inScattered += transmittance * source * (1.0 - stepTransmittance) / extinction;
        transmittance *= stepTransmittance;
    }
    return inScattered;
}

// The straight-alpha color that, blended over the scene beneath at the surface's opacity, gives the water's
// look: reflection + glint + (1 - F) * (bottom * transmittance + scattered light). The parts combine on screen
// (after tone mapping), since the scene beneath and the reflections come from the finished image.
vec3 waterColor(WaterSurface s, float albedo) {
    if (UnderwaterParams.x > 0.5) {
        vec2 size = vec2(textureSize(u_GBufferDepth, 0));
        bool flipY = screenFlipped;
        vec3 behind = texelFetch(u_SceneColor, ivec2(gl_FragCoord.xy), 0).rgb;
        // The reflection is of the water below: screen-space where the mirror ray finds it, else what Bedrock's
        // indirect specular falls back to, the sky probe in the mirror direction. Under water that probe is the
        // underwater atmosphere (SkyAtmosphere.underwater), so the totally reflecting surface outside Snell's
        // window shows the teal of the water, not black. Faded in by sky light at IBLParameters.z 0.5, as
        // above water.
        vec3 mirror = reflect(-s.v, s.n);
        vec4 traced = screenSpaceReflection(v_Position + s.n * 0.02, mirror, size, flipY);
        vec3 toSurface = exp(-UnderwaterMedium.rgb * length(v_Position));
        vec3 probe = vec3(0.0);
        if (AtmosphereZenith.w > 0.5) {
            float skyLevel = clamp((v_LightCoord.y * 256.0 - 8.0) / 240.0, 0.0, 1.0);
            float iblFade = pow(clamp((skyLevel * 15.0 - 12.0) / 3.0, 0.0, 1.0), 3.0) * 0.5;
            probe = toDisplay(locallyExposed(bedrockSkyReflection(mirror, s.roughness, normalize(SunDirection.xyz),
                    normalize(MoonDirection.xyz)) * iblFade));
        }
        vec3 reflection = mix(probe, toDisplay(locallyExposed(traced.rgb)) * toSurface, traced.a);
        vec3 target = clamp(s.fresnel * reflection + (1.0 - s.fresnel) * behind, 0.0, 1.0);
        return clamp((target - (1.0 - s.opacity) * behind) / max(s.opacity, 1e-3), 0.0, 1.0);
    }
    vec2 levels = clamp((v_LightCoord * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float sky = levels.y * levels.y;
    // Bedrock draws the water surface into its G-buffer (DoWaterSurfaceBuffer) and DeferredIndirectSpecular
    // reflects onto it as onto terrain: the sky probe faded in over sky light 12 to 15 (cubed) at
    // IBLParameters.z 0.5, replaced by SSR where that hits, all times F0 × A + B from the BRDF table, with
    // F0 = mix(0.04, albedo, metalness) for the grey water texel. DeferredWater's own Fresnel (F0 0.02) only
    // sets how much of the scene beneath shows through.
    float iblFade = pow(clamp((levels.y * 15.0 - 12.0) / 3.0, 0.0, 1.0), 3.0) * 0.5;
    float f0 = mix(0.04, albedo, s.metalness);
    float nDotVClamped = clamp(dot(s.n, s.v), 0.0, 1.0);
    float specularScale;
    if (Animation.z > 0.5) {
        vec2 ab = texture(u_BrdfLut, vec2(nDotVClamped, 1.0 - s.roughness)).rg;
        specularScale = f0 * ab.x + ab.y;
    } else {
        specularScale = (f0 + (max(1.0 - s.roughness, f0) - f0) * pow(1.0 - nDotVClamped, 5.0))
                * (1.0 - 0.7 * s.roughness);
    }
    vec3 sunDir = normalize(SunDirection.xyz);
    vec3 moonDir = normalize(MoonDirection.xyz);
    vec2 size = vec2(textureSize(u_GBufferDepth, 0));
    // Which way the framebuffer's y runs relative to clip space (screenFlipped, set in main).
    bool flipY = screenFlipped;

    // Reflection: the lighting pass's HDR scene where the mirror ray lands, else the sky (with the same horizon
    // handling as the deferred pass's reflections).
    vec3 mirror = reflect(-s.v, s.n);
    // Seen at a low angle, a ripple can tilt the mirror ray below the water's plane, where it can only find the
    // sky; DH's flat water beside it always reflects upwards and found the far shore (a seam between them).
    // Kept a little above the plane.
    if (UnderwaterParams.x < 0.5 && mirror.y < 0.02) {
        mirror = normalize(vec3(mirror.x, 0.02 + 0.5 * abs(mirror.y), mirror.z));
    }
    // The reflection, what lies beneath and the surface's fog are added in HDR (this renderer's units, before
    // exposure) and exposed, locally exposed and tone mapped once, as the lighting pass does for DH's water and
    // Bedrock's deferred water does: the marches read the lighting pass's HDR scene (u_LitFlags), not the
    // finished picture. (Scaled after the tone map, the near water's reflections of terrain differed from the
    // far water's beside it.)
    vec3 skyReflection;
    if (AtmosphereZenith.w > 0.5) {
        // Bedrock's sky probe is its sky (the atmosphere function), so the water reflects the same sky.
        skyReflection = bedrockSkyReflection(mirror, s.roughness, sunDir, moonDir) * iblFade;
    } else {
        float horizonShare = 0.3 * (1.0 - clamp(mirror.y * 0.5 + 0.5, 0.0, 1.0));
        skyReflection = mix(SkyColor.rgb, SkyHorizonColor.rgb, horizonShare) * iblFade;
        // Rougher texels see a blurrier sky; here the reflected sky leans towards its average.
        skyReflection = mix(skyReflection, mix(SkyColor.rgb, SkyHorizonColor.rgb, 0.15) * iblFade, s.roughness);
    }
    // Bedrock's SSR is mirror-only: full up to roughness 0.3, gone by 0.4.
    float ssrWeight = 1.0 - smoothstep(0.3, 0.4, s.roughness);
    // Bedrock's reflection passes' result for this pixel (ReflectionPass: the march, the gap fill and the
    // resolve accumulated over frames), premultiplied; where they found no water here, this fragment's own march.
    vec4 resolved = texture(u_Reflections, gl_FragCoord.xy / vec2(textureSize(u_GBufferDepth, 0)));
    vec4 traced;
    float nearHit;
    float farHit = 0.0;
    vec3 reflection;
    if (resolved.w > -1.5) {
        float confidence = clamp(resolved.w, 0.0, 1.0);
        traced = vec4(resolved.rgb, confidence);
        nearHit = confidence;
        reflection = resolved.rgb * ssrWeight + skyReflection * (1.0 - confidence * ssrWeight);
    } else {
        traced = ssrWeight > 0.0 ? screenSpaceReflection(v_Position + s.n * 0.02, mirror, size, flipY) : vec4(0.0);
        nearHit = traced.a;
        if (ssrWeight > 0.0 && traced.a < 0.5) {
            vec4 far = distantReflection(v_Position + s.n * 0.02, mirror, size, flipY);
            if (far.a > traced.a) {
                traced = far;
                farHit = far.a;
            }
        }
        reflection = mix(skyReflection, traced.rgb, traced.a * ssrWeight);
        traced.rgb *= traced.a;
    }
    // The reflection views (the flicker test's): 11 where each reflection comes from (red: the screen-space
    // march's hit, green: the far march's against Distant Horizons' depth, blue: the sky's share), 12 the
    // reflected sky alone, 13 the reflected scene alone. Drawn opaque (waterSurface).
    int reflectionView = int(DebugParams.w + 0.5);
    if (reflectionView == 11) {
        return vec3(nearHit * ssrWeight, farHit * ssrWeight, 0.15 + 0.85 * (1.0 - traced.a * ssrWeight));
    } else if (reflectionView == 12) {
        return toDisplay(locallyExposed(skyReflection));
    } else if (reflectionView == 13) {
        return toDisplay(locallyExposed(traced.rgb * ssrWeight));
    }

    // Sun glint.
    float nDotL = max(dot(s.n, sunDir), 0.0);
    vec3 glint = vec3(0.0);
    if (nDotL > 0.0) {
        float nDotV = max(dot(s.n, s.v), 1e-4);
        vec3 h = normalize(sunDir + s.v);
        float fresnel = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - max(dot(h, s.v), 0.0), 5.0);
        float r = max(s.roughness, 0.05);  // Bedrock's floor
        float geometry = smithG1(nDotV, r) * smithG1(nDotL, r);
        glint = SunColor.rgb * fresnel * ggx(max(dot(s.n, h), 0.0), r) * geometry / (4.0 * nDotV) * sky;
    }

    vec3 behind = texelFetch(u_SceneColor, ivec2(gl_FragCoord.xy), 0).rgb;
    // What lies beneath, from the lighting pass's HDR scene. Java draws entities and particles over that pass's
    // picture (and bloom adds to it), which its HDR scene doesn't hold: their difference is added through the
    // water after the tone map, as before.
    vec3 beneath = texelFetch(u_LitFlags, ivec2(gl_FragCoord.xy), 0).rgb;
    vec3 drawnOver = behind - toDisplay(locallyExposed(beneath));
    // Bedrock's water only absorbs (WaterExtinction); light scattered in the water comes from volumetric fog.
    vec3 transmitted = beneath * s.transmittance;
    if (s.scatters) {
        // Beyond the water column map, the water's own scattered light (the lighting pass adds it within).
        transmitted += waterInScatter(normalize(v_Position), s.path, s.beneathLit,
                // The surface's sky light (one level up for the water block's own dimming of it), as the
                // lighting pass takes its column's.
                min(VolumeCamera.x, s.beneathSky >= 0.0 ? waterSkyAccess(s.beneathSky)
                        : waterSkyAccess(clamp(levels.y + 1.0 / 15.0, 0.0, 1.0))));
    }
    vec3 surfaceLight = specularScale * reflection + glint;
    // DeferredWater fogs the surface's own light like terrain: towards the sky radiance (blended to the
    // underground colour where sky light is low) times its alpha F, by the distance fog. What shows through
    // was fogged by the lighting pass already.
    if (AtmosphereZenith.w > 0.5) {
        float fog = bedrockFogAmount(length(v_Position));
        if (fog > 0.0) {
            float open = max(sqrt(VolumeCamera.x) * 15.0 / 16.0, levels.y);
            vec3 fogColor = bedrockSkyRadiance(normalize(v_Position), sunDir, moonDir) * AtmosphereHorizon.w * open;
            surfaceLight = mix(surfaceLight, fogColor * s.fresnel, fog);
        }
        // Then the volumetric fog between the camera and the surface.
        vec3 fogTransmittance;
        vec3 fogLight = surfaceFog(normalize(v_Position), length(v_Position), fogTransmittance);
        surfaceLight = surfaceLight * fogTransmittance + fogLight * s.fresnel;
    }
    if (abs(DebugParams.w - 5.0) < 0.5) {
        transmitted = vec3(0.0); // the surface view: its reflection and glint alone
        drawnOver = vec3(0.0);
    }
    vec3 target = toDisplay(locallyExposed(surfaceLight + (1.0 - s.fresnel) * transmitted))
            + (1.0 - s.fresnel) * s.transmittance * drawnOver;
    target = clamp(target, 0.0, 1.0);
    return clamp((target - (1.0 - s.opacity) * behind) / max(s.opacity, 1e-3), 0.0, 1.0);
}

void main() {
    // Derivatives first, outside any branch: neighbouring pixels may disagree about being water.
    // Flat face normal from screen-space derivatives (turned to face the camera in waterSurface).
    vec3 dpx = dFdx(v_Position);
    vec3 dpy = dFdy(v_Position);
    // Against gl_FragCoord's own change down the screen: this backend's derivative of y can run against its
    // gl_FragCoord.y, and taken alone it turned every reflection's search upside down.
    vec4 clipHere = u_ProjectionMatrix * u_ModelViewMatrix * vec4(v_Position, 1.0);
    screenFlipped = dFdy(clipHere.y / clipHere.w) * dFdy(gl_FragCoord.y) < 0.0;
    vec3 faceNormal = normalize(cross(dpx, dpy));
    // Tangent frame from position and UV derivatives: the directions the texture's u and v run on this face.
    vec2 duvx = dFdx(v_TexCoord);
    vec2 duvy = dFdy(v_TexCoord);
    // The water sprite holds 16 texels per block: in surface-map texels, the larger of the pixel's two axes.
    surfaceFootprint = max(length(duvx), length(duvy)) / max(u_TexelSize.x, 1e-9);
    vec3 tangent = normalize(dpx * duvy.y - dpy * duvx.y + vec3(1e-9));
    vec3 bitangent = normalize(dpy * duvx.x - dpx * duvy.x + vec3(1e-9));
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);

    if (isWater()) {
        // The water's side faces at the edge of the loaded chunks: Sodium closes the water off where the next
        // chunk isn't loaded, and from above those faces showed through the surface as a blue wall hanging
        // under the chunk border, in front of Distant Horizons' water (which carries on behind them). Dropped
        // in the outer two chunks while DH's terrain is lit here; a side face that far out is only ever a
        // waterfall's or one against glass, a few pixels at most.
        if (Animation.w > 0.0 && UnderwaterParams.x < 0.5 && abs(faceNormal.y) < 0.2
                && length(v_Position.xz) > Animation.w) {
            discard;
        }
        // In a chunk Sodium has just started drawing (columnHeld), the lighting pass shows DH's picture for a few
        // frames; this water gives way to it until then.
        if (DistantParams.x > 0.5 && UnderwaterParams.x < 0.5 && columnHeld(v_Position)) {
            vec4 clip = u_ProjectionMatrix * u_ModelViewMatrix * vec4(v_Position, 1.0);
            if (distantDistanceAt(gl_FragCoord.xy / vec2(textureSize(u_GBufferDepth, 0)), clip.xy / clip.w) > 0.0) {
                discard;
            }
        }
        WaterSurface surface = waterSurface(faceNormal, tangent, bitangent);
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, surface.opacity);
        #else
        // The water texel's grey (untinted: Bedrock's G-buffer gets water_still_grey's colour), linear.
        float albedo = toLinear(vec3(dot(color.rgb, vec3(0.2126, 0.7152, 0.0722)))).r;
        vec3 shaded = abs(DebugParams.w - 6.0) < 0.5 ? vec3(0.95, 0.85, 0.0) : waterColor(surface, albedo);
        // Not through Sodium's fog (calculateFinalColor): the water's shading does Bedrock's fog itself, and
        // Sodium's blends a chunk's water towards Java's fog colour while the chunk fades in (fadeFactor) and
        // near the render distance's edge (a flash of other lighting as water chunks came in; a band before
        // DH's water).
        fragColor = vec4(shaded, surface.opacity);
        #endif
        return;
    }

    color *= v_Color;

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
