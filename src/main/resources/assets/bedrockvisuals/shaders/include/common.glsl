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
    vec4 WaterColumnOrigin;     // x, z: the camera's world x and z modulo 256, y: its height above the level's
                                // floor, w: 1 when the water column map is there (WaterColumns.java)
    mat4 ViewProjection;        // camera-relative world space to clip space (InverseViewProjection's inverse)
    mat4 PreviousViewProjection; // the previous frame's, relative to its own camera position
    vec4 CameraMotion;          // xyz: this frame's camera position minus the previous frame's
    vec4 SunMoonParams;         // x: how much of Bedrock's sun and moon shows (0: Java draws them), y: the moon's
                                // phase, z, w: the sun's and moon's emissive multipliers (SunMoon.java)
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
