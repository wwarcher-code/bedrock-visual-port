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
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const float PI = 3.14159265;

vec3 toLinear(vec3 srgb) {
    return pow(srgb, vec3(2.2));
}

// Narkowicz's ACES fit; Bedrock's own tone mapping replaces it in step 7.
vec3 tonemap(vec3 color) {
    return clamp((color * (2.51 * color + 0.03)) / (color * (2.43 * color + 0.59) + 0.14), 0.0, 1.0);
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

    // Sky fill brightest on upward faces, falling off with sky access.
    float skyFacing = 0.6 + 0.4 * n.y;
    color += diffuseColor * SkyColor.rgb * skyFacing * skyLight * skyLight;
    color += diffuseColor * AmbientColor.rgb;
    color += diffuseColor * BlockLightColor.rgb * pow(blockLight, 3.0) * 1.2;
    color += albedo * emissive * 2.0;

    fragColor = vec4(pow(tonemap(color), vec3(1.0 / 2.2)), 1.0);
}
