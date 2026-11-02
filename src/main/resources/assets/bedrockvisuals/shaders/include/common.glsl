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
    mat4 ShadowViewProjection;  // camera-relative world space to shadow clip space (ShadowProjection.java)
    vec4 ShadowParams;          // x: map valid, y: texel size in UV, z: normal offset in blocks, w: cast by the moon
    vec4 DebugParams;           // x: draw the shadow test instead of lit color
    vec4 SkyHorizonColor;       // sky fill seen towards the horizon; SkyColor is the zenith's
    vec4 CameraGridOffset;      // the camera's world position modulo 16, to put positions on the texel grid
};

// Bedrock's color grading and tone mapping for the player's biome (ColorGrading.java).
layout(std140) uniform GradingData {
    vec4 MidContrast, MidGain, MidOffset, MidSaturation;
    vec4 ShadowContrast, ShadowGain, ShadowOffset, ShadowSaturation;
    vec4 HighContrast, HighGain, HighOffset, HighSaturation;
    vec4 Gamma;        // final encoding, after tone mapping
    vec4 Temperature;  // per-channel multiplier
    vec4 Bands;        // x: shadows enabled, y: shadow max, z: highlights enabled, w: highlight min
    vec4 ToneMapping;  // x: operator (ColorGradingSettings.ToneMapping order), y: average luminance
};

const float PI = 3.14159265;

vec3 toLinear(vec3 srgb) {
    return pow(srgb, vec3(2.2));
}

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

// One set of grading parameters, around the scene's average luminance as Bedrock's documentation describes:
// saturation about the pixel's luminance, contrast about the average, then gain and an offset scaled by it.
vec3 gradeWith(vec3 c, vec3 contrast, vec3 gain, vec3 offset, vec3 saturation, float average) {
    c = max(mix(vec3(dot(c, LUMA)), c, saturation), 0.0);
    c = pow(c / average, contrast) * average;
    return max(c * gain + offset * average, 0.0);
}

vec3 grade(vec3 c) {
    c *= Temperature.rgb;
    float average = ToneMapping.y;
    float luminance = dot(c, LUMA);
    vec3 graded = gradeWith(c, MidContrast.rgb, MidGain.rgb, MidOffset.rgb, MidSaturation.rgb, average);
    // Shadow and highlight bands replace the midtones below and above their thresholds, blended softly.
    if (Bands.x > 0.5) {
        float limit = Bands.y * average;
        float weight = 1.0 - smoothstep(limit * 0.8, limit * 1.25, luminance);
        graded = mix(graded, gradeWith(c, ShadowContrast.rgb, ShadowGain.rgb, ShadowOffset.rgb, ShadowSaturation.rgb, average), weight);
    }
    if (Bands.z > 0.5) {
        float limit = Bands.w * average;
        float weight = smoothstep(limit * 0.8, limit * 1.25, luminance);
        graded = mix(graded, gradeWith(c, HighContrast.rgb, HighGain.rgb, HighOffset.rgb, HighSaturation.rgb, average), weight);
    }
    return graded;
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

// HDR linear color to the screen: Bedrock's grading, tone map, then its gamma.
vec3 toDisplay(vec3 color) {
    return pow(clamp(tonemap(grade(color)), 0.0, 1.0), 1.0 / max(Gamma.rgb, vec3(0.1)));
}

#endif
