#version 460 core

// Water variant of Sodium's terrain fragment shader, for the translucent layer in each of Minecraft's OIT
// stages. Glass, ice and other translucent blocks come out exactly as Sodium draws them. Water (its still
// and flowing sprites) is shaded as Bedrock's Vibrant Visuals water: clear water that absorbs light along
// the path to whatever lies beneath (read from the G-buffer's depth), a sky reflection weighted by Fresnel,
// a sun glint, and sky light scattered back out of the water body. Every stage must see the same opacity, so
// it depends only on geometry, depth and the water settings (WaterPass.java).

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_material.glsl>
#include <minecraft:oit.glsl>
#include <bedrockvisuals:common.glsl>

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_FragDistance;
layout(location = 3) in float fadeFactor;
layout(location = 4) in vec2 v_LightCoord;
layout(location = 5) in vec3 v_Position;

uniform sampler2D u_BlockTex;
uniform sampler2D u_GBufferDepth;  // opaque terrain behind the water, reversed depth, 0 where there is none
uniform sampler2D u_GBufferAlbedo;

layout(std140) uniform WaterData {
    vec4 StillRect;   // u0, v0, u1, v1 of the water_still sprite in the block atlas
    vec4 FlowRect;    // the same for water_flow
    vec4 Absorption;  // per block, linear RGB
    vec4 Scattering;  // per block, linear RGB; w: 1 when water shading is on
};

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Water's reflectance looking straight down; about 2% for an air-water surface.
const float WATER_F0 = 0.02;
// Roughness for the sun glint: smooth, but wide enough to read at Minecraft's scale.
const float GLINT_ROUGHNESS = 0.12;
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
    return Scattering.w > 0.5 && (inRect(v_TexCoord, StillRect) || inRect(v_TexCoord, FlowRect));
}

// Distance from the camera along the view axis, from a depth value. With w = -z, depth = (P22 z + P32) / -z,
// so -z = P32 / (depth + P22); this holds for reversed and infinite projections alike.
float viewDistance(float depth) {
    return u_ProjectionMatrix[3][2] / (depth + u_ProjectionMatrix[2][2]);
}

struct WaterSurface {
    vec3 n;
    vec3 v;
    float fresnel;
    vec3 transmittance;  // along the path from the surface to the bottom
    float opacity;       // what the OIT stages blend with; the same in every stage
};

WaterSurface waterSurface(vec3 faceNormal) {
    WaterSurface s;
    s.n = faceNormal;
    if (dot(s.n, v_Position) > 0.0) {
        s.n = -s.n;
    }
    s.v = normalize(-v_Position);
    float nDotV = max(dot(s.n, s.v), 1e-4);
    s.fresnel = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - nDotV, 5.0);

    float opaque = texelFetch(u_GBufferDepth, ivec2(gl_FragCoord.xy), 0).r;
    float path = BOTTOMLESS;
    if (opaque > 0.0) {
        // The bottom lies on the same view ray, this much farther along the view axis.
        float ratio = viewDistance(opaque) / max(viewDistance(gl_FragCoord.z), 1e-4);
        path = min(length(v_Position) * max(ratio - 1.0, 0.0), BOTTOMLESS);
    }
    s.transmittance = exp(-(Absorption.rgb + Scattering.rgb) * path);
    // OIT blends one opacity for all channels. Using the least-transmitted channel keeps the color solved
    // for below (waterColor) non-negative; the per-channel tint goes into that color.
    float leastTransmitted = min(s.transmittance.r, min(s.transmittance.g, s.transmittance.b));
    s.opacity = clamp(1.0 - (1.0 - s.fresnel) * leastTransmitted, 0.0, 1.0);
    return s;
}

// The straight-alpha color that, blended over the lit bottom at the surface's opacity, gives the water's
// look: reflection + glint + (1 - F) * (bottom * transmittance + scattered light).
vec3 waterColor(WaterSurface s) {
    vec2 levels = clamp((v_LightCoord * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float sky = levels.y * levels.y;
    vec3 sunDir = normalize(SunDirection.xyz);
    vec3 moonDir = normalize(MoonDirection.xyz);

    // Sky reflection, with the same horizon handling as the deferred pass's reflections.
    vec3 mirror = reflect(-s.v, s.n);
    float horizonShare = 0.3 * (1.0 - clamp(mirror.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 reflection = mix(SkyColor.rgb, SkyHorizonColor.rgb, horizonShare) * sky;

    // Sun glint.
    float nDotL = max(dot(s.n, sunDir), 0.0);
    vec3 glint = vec3(0.0);
    if (nDotL > 0.0) {
        float nDotV = max(dot(s.n, s.v), 1e-4);
        vec3 h = normalize(sunDir + s.v);
        float fresnel = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - max(dot(h, s.v), 0.0), 5.0);
        float geometry = smithG1(nDotV, GLINT_ROUGHNESS) * smithG1(nDotL, GLINT_ROUGHNESS);
        glint = SunColor.rgb * fresnel * ggx(max(dot(s.n, h), 0.0), GLINT_ROUGHNESS) * geometry / (4.0 * nDotV) * sky;
    }

    // Light inside the water: sun, moon and sky from above, plus the ambient floor, scattered back out in
    // proportion to the water's single-scattering albedo.
    vec3 extinction = Absorption.rgb + Scattering.rgb;
    vec3 inLight = (SunColor.rgb * max(sunDir.y, 0.0) + MoonColor.rgb * max(moonDir.y, 0.0) + SkyColor.rgb) * sky
            + AmbientColor.rgb;
    vec3 scattered = inLight * (Scattering.rgb / max(extinction, vec3(1e-5))) * (1.0 - s.transmittance);

    // What the deferred pass drew beneath the water, estimated from the G-buffer's albedo with the light an
    // upward face gets. Only the estimate's error, scaled by (1 - opacity), reaches the screen.
    vec3 bottomAlbedo = toLinear(texelFetch(u_GBufferAlbedo, ivec2(gl_FragCoord.xy), 0).rgb);
    vec3 bottom = bottomAlbedo * ((SunColor.rgb * max(sunDir.y, 0.0) + SkyColor.rgb) * sky + AmbientColor.rgb);

    vec3 seen = s.fresnel * reflection + glint + (1.0 - s.fresnel) * (bottom * s.transmittance + scattered);
    vec3 target = toDisplay(seen);
    vec3 behind = toDisplay(bottom);
    return clamp((target - (1.0 - s.opacity) * behind) / max(s.opacity, 1e-3), 0.0, 1.0);
}

void main() {
    // Derivatives first, outside any branch: neighbouring pixels may disagree about being water.
    // Flat face normal from screen-space derivatives (turned to face the camera in waterSurface).
    vec3 faceNormal = normalize(cross(dFdx(v_Position), dFdy(v_Position)));
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);

    if (isWater()) {
        WaterSurface surface = waterSurface(faceNormal);
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, surface.opacity);
        #else
        fragColor = calculateFinalColor(vec4(waterColor(surface), surface.opacity));
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
