#version 330
#extension GL_ARB_separate_shader_objects : require

// Deferred lighting: turns the G-buffer into lit color. Terrain only. Within the shadow map's range the sun
// (or moon) is tested against it; beyond it, sky light stands in for sun visibility.

uniform sampler2D Albedo;
uniform sampler2D Normal;
uniform sampler2D Material;
uniform sampler2D Light;
uniform sampler2D Depth;
uniform sampler2D ShadowMap;

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

// TUNE: share of light reaching the ground that bounces back onto faces turned towards it.
const float GROUND_BOUNCE = 0.2;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

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

// Bedrock's six operators, numbered as ColorGradingSettings.ToneMapping. Reinhard's extended forms use a white
// point of 4. "Generic" is Mojang's hand-tuned curve with no published formula; until the fidelity pass,
// it is approximated as ACES applied half per channel, half to luminance, to keep more hue saturation in
// bright areas as Bedrock's documentation describes.
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
    vec3 byLuminance = c * (aces(vec3(luminance)).x / max(luminance, 1e-5));
    return clamp(mix(aces(c), byLuminance, 0.5), 0.0, 1.0);
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

vec3 directLight(vec3 n, vec3 v, vec3 l, vec3 radiance, vec3 diffuseColor, vec3 f0, float roughness) {
    float nDotL = max(dot(n, l), 0.0);
    if (nDotL <= 0.0) {
        return vec3(0.0);
    }
    vec3 h = normalize(l + v);
    float nDotV = max(dot(n, v), 1e-4);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    float geometry = smithG1(nDotV, roughness) * smithG1(nDotL, roughness);
    vec3 specular = fresnel * ggx(max(dot(n, h), 0.0), roughness) * geometry / (4.0 * nDotV * nDotL);
    return ((1.0 - fresnel) * diffuseColor + specular) * radiance * nDotL;
}

// Fraction of a 3x3 texel neighbourhood that sees the light, or -1 outside the map. Depth is reversed in the
// map too: larger values are nearer the light, so a point is lit when nothing nearer the light was stored.
float shadowVisibility(vec3 position, vec3 n, vec3 towardsLight, out float edgeFade) {
    edgeFade = 0.0;
    if (ShadowParams.x < 0.5) {
        return -1.0;
    }
    // Push the lookup out along the normal, more on faces that graze the light, against self-shadowing.
    float grazing = 1.0 - max(dot(n, towardsLight), 0.0);
    vec4 clip = ShadowViewProjection * vec4(position + n * ShadowParams.z * (1.0 + 2.0 * grazing), 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return -1.0;
    }
    // Blend towards the sky-light fallback over the outer 5% of the map on each side.
    vec2 edge = min(uv, 1.0 - uv);
    edgeFade = smoothstep(0.0, 0.05, min(edge.x, edge.y));

    const float BIAS = 0.0003; // about 0.15 blocks of the map's depth range
    float lit = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float stored = texture(ShadowMap, uv + vec2(x, y) * ShadowParams.y).r;
            lit += clip.z >= stored - BIAS ? 1.0 : 0.0;
        }
    }
    return lit / 9.0;
}

void main() {
    float depth = texture(Depth, texCoord).r;
    if (depth <= 0.0) {
        discard; // reversed depth: 0 is the cleared far plane, so no terrain here
    }

    vec3 albedo = toLinear(texture(Albedo, texCoord).rgb);
    vec3 n = normalize(texture(Normal, texCoord).rgb * 2.0 - 1.0);
    vec4 mers = texture(Material, texCoord);
    vec2 levels = texture(Light, texCoord).rg;

    float metalness = mers.r;
    float emissive = mers.g;
    float roughness = clamp(mers.b, 0.05, 1.0);

    vec4 world = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
    vec3 v = normalize(-world.xyz / world.w); // the camera is at the origin

    // Minecraft's light coordinates are (level * 16 + 8) / 256; map back to 0..1.
    vec2 light = clamp((levels * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float blockLight = light.x;
    float skyLight = light.y;

    vec3 diffuseColor = albedo * (1.0 - metalness);
    vec3 f0 = mix(vec3(0.04), albedo, metalness);
    // Sky light alone: the stand-in beyond the shadow map, and a guard inside it, since Sodium only hands the
    // shadow pass the chunks the camera sees, so a cave's roof may be missing from the map.
    float skyFallback = pow(skyLight, 4.0);
    float skyGuard = smoothstep(0.3, 0.8, skyLight);

    vec3 position = world.xyz / world.w;
    vec3 sunDir = normalize(SunDirection.xyz);
    vec3 moonDir = normalize(MoonDirection.xyz);
    bool moonCasts = ShadowParams.w > 0.5;
    float edgeFade;
    float mapped = shadowVisibility(position, n, moonCasts ? moonDir : sunDir, edgeFade);
    float casterVisibility = mapped < 0.0 ? skyFallback : mix(skyFallback, mapped * skyGuard, edgeFade);
    if (DebugParams.x > 0.5) {
        fragColor = vec4(mapped < 0.0 ? vec3(1.0, 0.0, 0.0) : vec3(mapped), 1.0);
        return;
    }
    float sunVisibility = moonCasts ? skyFallback : casterVisibility;
    float moonVisibility = moonCasts ? casterVisibility : skyFallback;

    vec3 color = directLight(n, v, sunDir, SunColor.rgb * sunVisibility, diffuseColor, f0, roughness);
    color += directLight(n, v, moonDir, MoonColor.rgb * moonVisibility, diffuseColor, f0, roughness);

    // Sky fill from the hemisphere a face sees: upward faces see mostly the zenith, walls half the sky, mostly
    // its brighter horizon, and their lower half the ground, which bounces back some sun and sky.
    float upness = n.y * 0.5 + 0.5;
    vec3 skySeen = mix(SkyHorizonColor.rgb, SkyColor.rgb, upness) * upness;
    // The ground in front of a shaded wall is usually sunlit, so the bounce ignores this pixel's own shadow.
    vec3 groundLight = SunColor.rgb * max(sunDir.y, 0.0) * skyFallback + SkyColor.rgb;
    vec3 bounce = GROUND_BOUNCE * groundLight * (1.0 - upness);
    color += diffuseColor * (skySeen + bounce) * skyLight * skyLight;

    // Reflected sky: smooth and metallic surfaces mirror their surroundings. Until there are reflections, the
    // sky gradient in the mirror direction stands in, fading out with roughness and sky access.
    float nDotV = max(dot(n, v), 1e-4);
    vec3 mirror = reflect(-v, n);
    vec3 skyInMirror = mix(SkyHorizonColor.rgb, SkyColor.rgb, clamp(mirror.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 grazingF0 = max(vec3(1.0 - roughness), f0);
    vec3 skyFresnel = f0 + (grazingF0 - f0) * pow(1.0 - nDotV, 5.0);
    color += skyInMirror * skyFresnel * (1.0 - 0.7 * roughness) * skyLight * skyLight;
    color += diffuseColor * AmbientColor.rgb;
    color += diffuseColor * BlockLightColor.rgb * pow(blockLight, 3.0) * 1.2;
    color += albedo * emissive * 2.0;

    fragColor = vec4(pow(clamp(tonemap(grade(color)), 0.0, 1.0), 1.0 / max(Gamma.rgb, vec3(0.1))), 1.0);
}
