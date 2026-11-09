#version 330
#extension GL_ARB_separate_shader_objects : require

// Lights one face of the scene probe (SceneProbe.java), as Bedrock's SkyProbeDeferredShading lights its
// reflection probe from the probe's own G-buffer: the terrain's diffuse light only (the probe is read for
// reflections, at most of their roughness), in this renderer's units before exposure. Alpha 1 where terrain was
// drawn, 0 for the sky, which the probe's base pass fills in.
//
// The same terms as the lighting pass: Lambert sun and moon (albedo / π × illuminance × N·L), shadowed by the
// outer cascade where it reaches and by the sky light beyond it; the indirect light max(block + sky × s ×
// mix(s⁴, s², camera sky), ambient); emissive.

uniform sampler2D ProbeAlbedo;
uniform sampler2D ProbeNormal;
uniform sampler2D ProbeMaterial;
uniform sampler2D ProbeLight;
uniform sampler2D ProbeDepth;
uniform sampler2D ShadowMap3;

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>

layout(std140) uniform ProbeFace {
    mat4 ProbeViewProjection;
    mat4 ProbeInverseViewProjection;
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

// The outer cascade's test at p (0 shadowed, 1 lit), or -1 outside it.
float probeShadow(vec3 p, vec3 n) {
    if (ShadowParams.x < 0.5) {
        return -1.0;
    }
    vec4 clip = ShadowViewProjection[3] * vec4(p + n * 0.1, 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return -1.0;
    }
    float texel = ShadowParams.y;
    float lit = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 offset = (vec2(float(i & 1), float(i >> 1)) - 0.5) * texel;
        lit += clip.z >= texture(ShadowMap3, uv + offset).r - ShadowCascadeBias[3] ? 1.0 : 0.0;
    }
    return lit * 0.25;
}

void main() {
    float depth = texture(ProbeDepth, texCoord).r;
    if (depth <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }
    vec4 world = ProbeInverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
    vec3 position = world.xyz / world.w;
    vec3 albedo = toLinear(texture(ProbeAlbedo, texCoord).rgb);
    vec3 n = normalize(texture(ProbeNormal, texCoord).rgb * 2.0 - 1.0);
    vec4 mers = texture(ProbeMaterial, texCoord);
    vec2 levels = texture(ProbeLight, texCoord).rg;
    vec2 light = clamp((levels * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float blockLight = light.x;
    float skyLight = light.y;
    vec3 diffuse = albedo * (1.0 - mers.r);

    bool moonCasts = ShadowParams.w > 0.5;
    vec3 l = normalize(moonCasts ? MoonDirection.xyz : SunDirection.xyz);
    vec3 radiance = moonCasts ? MoonColor.rgb : SunColor.rgb;
    float mapped = probeShadow(position, n);
    float skyGuard = skyLight > 0.0 ? 1.0 : 0.0;
    float visibility = mapped < 0.0 ? pow(skyLight, 4.0) : mapped * skyGuard;
    vec3 color = diffuse / PI * radiance * max(dot(n, l), 0.0) * visibility;

    float cameraSky = sqrt(VolumeCamera.x) * 15.0 / 16.0;
    float skyCurve = skyLight * mix(pow(skyLight, 4.0), skyLight * skyLight, cameraSky);
    vec3 indirect = max(BlockLightColor.rgb * blockLightColor(blockLight) + SkyColor.rgb * skyCurve, AmbientColor.rgb);
    color += diffuse * indirect;
    color += albedo * mers.g * 2.0;

    // The distance fog, towards the sky behind, as the lighting pass fogs terrain.
    float fog = AtmosphereZenith.w > 0.5 ? bedrockFogAmount(length(position)) : 0.0;
    if (fog > 0.0) {
        vec3 sky = bedrockSkyRadiance(normalize(position), normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
                * AtmosphereHorizon.w;
        color = mix(color, sky, fog);
    }
    fragColor = vec4(color, 1.0);
}
