#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock-lit variant of Minecraft's entity shader (EntityPass.java). The texture, tint, overlay (the red hurt
// flash), dissolve, glint and order-independent transparency all work as in Minecraft's; the lighting is the
// lighting pass's instead of the lightmap and fixed side shading: the sun and moon through the shadow cascades,
// Bedrock's sky and block light, the water between the camera and the entity, and the distance fog, then the
// local exposure and grading the rest of the frame got (entities are drawn after the lighting pass, into the
// graded image).

#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>
#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>
#define LOCAL_EXPOSURE_APPLY
#include <bedrockvisuals:local_exposure.glsl>
uniform sampler2D WaterColumnMap;
#define WATER_COLUMNS WaterColumnMap
#include <bedrockvisuals:water_columns.glsl>

uniform sampler2D Sampler0;

#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

uniform sampler2D ShadowMap0;
uniform sampler2D ShadowMap1;
uniform sampler2D ShadowMap2;
uniform sampler2D ShadowMap3;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) in vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlayColor;
#endif

layout(location = 6) in vec2 texCoord0;
#ifdef GLINT
layout(location = 7) in vec2 texCoordGlint;
#endif
layout(location = 8) in vec3 bvPosition;
layout(location = 9) in vec3 bvNormal;
layout(location = 10) in vec4 bvColor;
layout(location = 11) in vec2 bvLight;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// One cascade's test, as the lighting pass makes it (deferred.fsh, cascadeTest): 1 lit, 0 shadowed, -1 outside.
float bvCascade(sampler2D map, int cascade, vec3 p, float slope) {
    vec4 clip = ShadowViewProjection[cascade] * vec4(p, 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return -1.0;
    }
    float bias = ShadowCascadeBias[cascade] + slope * ShadowCascadeSlope[cascade];
    return clip.z >= texture(map, uv).r - bias ? 1.0 : 0.0;
}

// The darkest of the cascades the point falls in, faded out over the shadow range's last 8 blocks; -1 when
// there's no shadow map or the point is in none of them.
float bvShadow(vec3 p, vec3 n, vec3 towardsLight) {
    if (ShadowParams.x < 0.5) {
        return -1.0;
    }
    float grazing = clamp(1.0 - dot(n, towardsLight), 0.0, 1.0);
    vec3 q = p + n * ShadowParams.z * grazing;
    float nDotL = max(dot(n, towardsLight), 0.04);
    float slope = sqrt(1.0 - nDotL * nDotL) / nDotL;
    float visibility = 2.0;
    float t;
    t = bvCascade(ShadowMap0, 0, q, slope); if (t >= 0.0) visibility = min(visibility, t);
    t = bvCascade(ShadowMap1, 1, q, slope); if (t >= 0.0) visibility = min(visibility, t);
    t = bvCascade(ShadowMap2, 2, q, slope); if (t >= 0.0) visibility = min(visibility, t);
    t = bvCascade(ShadowMap3, 3, q, slope); if (t >= 0.0) visibility = min(visibility, t);
    if (visibility > 1.5) {
        return -1.0;
    }
    float edgeFade = 1.0 - smoothstep(120.0, 128.0, length(p.xz));
    return mix(1.0, visibility, edgeFade);
}

// The entity's light, before exposure: Lambert for the sun and moon (Bedrock's direct diffuse; entities are
// rough dielectrics), Bedrock's indirect light from the block and sky light levels (deferred.fsh), and an
// emissive pass straight through.
vec3 bvLit(vec3 albedo, vec3 n, vec3 v) {
    #ifdef EMISSIVE
    return albedo * 2.0;
    #else
    float blockLight = bvLight.x;
    float skyLight = bvLight.y;
    // Entities are two-sided in places (leaves on a model, banners): light the side facing the camera.
    if (dot(n, v) < 0.0) {
        n = -n;
    }
    vec3 sunDir = normalize(SunDirection.xyz);
    vec3 moonDir = normalize(MoonDirection.xyz);
    bool moonCasts = ShadowParams.w > 0.5;
    float skyFallback = pow(skyLight, 4.0);
    float skyGuard = skyLight > 0.0 ? 1.0 : 0.0;
    float mapped = bvShadow(bvPosition, n, moonCasts ? moonDir : sunDir);
    float casterVisibility = mapped < 0.0 ? skyFallback : mapped * skyGuard;
    float sunVisibility = moonCasts ? skyFallback : casterVisibility;
    float moonVisibility = moonCasts ? casterVisibility : skyFallback;
    vec3 color = albedo / 3.14159265 * (SunColor.rgb * sunVisibility * max(dot(n, sunDir), 0.0)
            + MoonColor.rgb * moonVisibility * max(dot(n, moonDir), 0.0));
    float cameraSky = sqrt(VolumeCamera.x) * 15.0 / 16.0;
    float skyCurve = skyLight * mix(pow(skyLight, 4.0), skyLight * skyLight, cameraSky);
    vec3 indirect = max(BlockLightColor.rgb * blockLightColor(blockLight) + SkyColor.rgb * skyCurve, AmbientColor.rgb);
    return color + albedo * indirect;
    #endif
}

// The water between the camera and the entity, and the distance fog, as for terrain.
vec3 bvMedium(vec3 color) {
    vec3 dir = normalize(bvPosition);
    float distance = length(bvPosition);
    if (UnderwaterParams.x > 0.5) {
        color *= underwaterTransmittance(dir, distance);
    } else {
        // Under mapped water seen from above: dimmed by the water above it along the view, plus the light the
        // water scatters in front of it (the water pass leaves what's under mapped water to this).
        float depth = depthBelowWaterSurface(waterTestPoint(bvPosition));
        if (depth >= 0.0) {
            float path = min(depth / max(-dir.y, 0.05), 64.0);
            color = color * exp(-waterScatterExtinction() * path) + waterInScatter(dir, path, 1.0,
                    waterSkyAccess(max(waterColumnSkyLight(waterTestPoint(bvPosition)), 0.0)));
        }
    }
    if (AtmosphereZenith.w > 0.5) {
        float fog = bedrockFogAmount(distance);
        if (fog > 0.0) {
            vec3 sky = bedrockSkyRadiance(dir, normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
                    * AtmosphereHorizon.w;
            color = mix(color, sky, fog);
        }
    }
    return color;
}

vec4 calculateFinalColor(vec4 color) {
    #ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #endif

    // Bedrock's lighting in place of the lightmap: the texel and tint are the albedo (sRGB).
    vec3 albedo = toLinear(clamp(color.rgb, 0.0, 1.0));
    vec3 lit = bvMedium(bvLit(albedo, normalize(bvNormal), normalize(-bvPosition)));
    color.rgb = toDisplay(locallyExposed(lit));
    #if !defined(EMISSIVE) && !defined(OIT_ALPHA_ONLY)
    // Never taken (the sky's on flag is 0 or 1); keeps Minecraft's lightmap input in use, as its binding is.
    if (AtmosphereZenith.w < -1.0) {
        color *= lightMapColor;
    }
    #endif

    #ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    // Matches BlendFuntion.GLINT
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    // Minecraft's fog is replaced by Bedrock's (above); the inputs stay referenced as their bindings are.
    if (AtmosphereZenith.w < -1.0) {
        color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart,
                FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    }
    return color;
}

void main() {
    vec4 color = texture(Sampler0, texCoord0);

    #ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
    #endif

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    // The tint without Minecraft's side shading (Bedrock's light does the shading); the vertex colour stays
    // referenced for the never-taken branch below.
    vec4 faceVertexColor = bvColor;
    #ifdef PER_FACE_LIGHTING
    if (AtmosphereZenith.w < -1.0) {
        faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
    }
    #else
    if (AtmosphereZenith.w < -1.0) {
        faceVertexColor = vertexColor;
    }
    #endif

    #ifdef DISSOLVE
    if (faceVertexColor.a < texture(DissolveMaskSampler, texCoord0).a) {
        discard;
    }
    // The dissolve effect entirely replaces translucency
    faceVertexColor.a = 1.0;
    #endif

    color *= faceVertexColor * ColorModulator;

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
