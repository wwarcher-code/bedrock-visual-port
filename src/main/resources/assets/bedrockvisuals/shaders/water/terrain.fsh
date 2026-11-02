#version 460 core

// Water variant of Sodium's terrain fragment shader, for the translucent layer in each of Minecraft's OIT
// stages. Glass, ice and other translucent blocks come out exactly as Sodium draws them. Water (its still
// and flowing sprites) is shaded as Bedrock's Vibrant Visuals water: clear water that absorbs light along
// the path to whatever lies beneath (read from the G-buffer's depth), screen-space reflections of the scene
// falling back to the sky, weighted by Fresnel, a sun glint, and light scattered back out of the water.
// Every stage must see the same opacity, so it depends only on geometry, depth and the water settings
// (WaterPass.java).

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_material.glsl>
#include <minecraft:oit.glsl>
#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_FragDistance;
layout(location = 3) in float fadeFactor;
layout(location = 4) in vec2 v_LightCoord;
layout(location = 5) in vec3 v_Position;

uniform sampler2D u_BlockTex;
uniform sampler2D u_GBufferDepth;  // opaque terrain behind the water, reversed depth, 0 where there is none
uniform sampler2D u_SceneColor;    // the lit opaque scene (sky and terrain), copied before translucency

layout(std140) uniform WaterData {
    vec4 StillRect;   // u0, v0, u1, v1 of the water_still sprite in the block atlas
    vec4 FlowRect;    // the same for water_flow
    vec4 Absorption;  // per block, linear RGB
    vec4 Scattering;  // per block, linear RGB; w: 1 when water shading is on
    vec4 Animation;   // x: current frame of the surface maps, y: frames in them
};

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
    return Scattering.w > 0.5 && (inRect(v_TexCoord, StillRect) || inRect(v_TexCoord, FlowRect));
}

// Distance from the camera along the view axis, from a depth value. With w = -z, depth = (P22 z + P32) / -z,
// so -z = P32 / (depth + P22); this holds for reversed and infinite projections alike.
float viewDistance(float depth) {
    return u_ProjectionMatrix[3][2] / (depth + u_ProjectionMatrix[2][2]);
}

struct WaterSurface {
    vec3 n;              // with the surface map's per-texel tilt
    float roughness;     // the surface map's per-texel roughness
    vec3 v;
    float fresnel;
    vec3 transmittance;  // along the path from the surface to the bottom
    float opacity;       // what the OIT stages blend with; the same in every stage
};

// This texel's normal and MERS from Bedrock's surface maps, for the current animation frame. The water
// sprite's UVs cover 16 texels per block (32 for the flowing sprite, which spans two).
void surfaceTexel(out vec3 tangentNormal, out float roughness) {
    vec4 rect = inRect(v_TexCoord, StillRect) ? StillRect : FlowRect;
    vec2 local = (v_TexCoord - rect.xy) / max(rect.zw - rect.xy, vec2(1e-6));
    float spriteTexels = max(round((rect.z - rect.x) / u_TexelSize.x), 1.0);
    ivec2 mapSize = textureSize(u_WaterNormal, 0);
    int frameSize = max(mapSize.x, 1);
    ivec2 texel = ivec2(mod(floor(local * spriteTexels), float(frameSize)));
    texel.y += int(Animation.x) * frameSize;
    texel = clamp(texel, ivec2(0), mapSize - 1);
    tangentNormal = normalize(texelFetch(u_WaterNormal, texel, 0).xyz * 2.0 - 1.0);
    roughness = texelFetch(u_WaterMers, texel, 0).b;
}

WaterSurface waterSurface(vec3 faceNormal, vec3 tangent, vec3 bitangent) {
    WaterSurface s;
    vec3 face = faceNormal;
    if (dot(face, v_Position) > 0.0) {
        face = -face;
    }
    vec3 tangentNormal;
    surfaceTexel(tangentNormal, s.roughness);
    s.n = normalize(tangent * tangentNormal.x + bitangent * tangentNormal.y + face * tangentNormal.z);
    if (dot(s.n, v_Position) > 0.0) {
        s.n = face;
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

bool onScreen(vec2 pixel, vec2 size) {
    return all(greaterThanEqual(pixel, vec2(0.0))) && all(lessThan(pixel, size));
}

vec4 screenSpaceReflection(vec3 origin, vec3 direction, vec2 size, bool flipY) {
    float stepLength = 0.25;
    float travelled = 0.0;
    vec3 previous = origin;
    for (int i = 0; i < SSR_STEPS && travelled < SSR_MAX_DISTANCE; i++) {
        travelled += stepLength;
        vec3 point = origin + direction * travelled;
        vec3 screen = toScreen(point, size, flipY);
        if (screen.z <= 0.0 || !onScreen(screen.xy, size)) {
            return vec4(0.0);
        }
        float depth = texelFetch(u_GBufferDepth, ivec2(screen.xy), 0).r;
        if (depth > 0.0 && screen.z > viewDistance(depth)) {
            // Behind the depth buffer: a hit unless far behind it (the ray passed behind an object).
            if (screen.z - viewDistance(depth) > max(stepLength * 2.0, 1.0)) {
                return vec4(0.0);
            }
            vec3 front = previous;
            vec3 back = point;
            for (int j = 0; j < SSR_REFINE; j++) {
                vec3 middle = (front + back) * 0.5;
                vec3 m = toScreen(middle, size, flipY);
                float d = onScreen(m.xy, size) ? texelFetch(u_GBufferDepth, ivec2(m.xy), 0).r : 0.0;
                if (d > 0.0 && m.z > viewDistance(d)) {
                    back = middle;
                } else {
                    front = middle;
                }
            }
            vec2 hit = clamp(toScreen(back, size, flipY).xy, vec2(0.0), size - 1.0);
            // Fade towards the screen's edges, where the reflected scene runs out.
            vec2 edge = min(hit, size - hit) / size;
            return vec4(texelFetch(u_SceneColor, ivec2(hit), 0).rgb, smoothstep(0.0, 0.08, min(edge.x, edge.y)));
        }
        previous = point;
        stepLength *= 1.18;
    }
    return vec4(0.0);
}

// The straight-alpha color that, blended over the scene beneath at the surface's opacity, gives the water's
// look: reflection + glint + (1 - F) * (bottom * transmittance + scattered light). The parts combine on screen
// (after tone mapping), since the scene beneath and the reflections come from the finished image.
vec3 waterColor(WaterSurface s) {
    vec2 levels = clamp((v_LightCoord * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float sky = levels.y * levels.y;
    vec3 sunDir = normalize(SunDirection.xyz);
    vec3 moonDir = normalize(MoonDirection.xyz);
    vec2 size = vec2(textureSize(u_GBufferDepth, 0));
    // Which way the framebuffer's y runs relative to clip space: whichever puts this fragment where it is.
    vec2 self = toScreen(v_Position, size, false).xy;
    bool flipY = abs(self.y - gl_FragCoord.y) > abs(size.y - self.y - gl_FragCoord.y);

    // Reflection: the scene where the mirror ray lands, else the sky gradient (with the same horizon handling
    // as the deferred pass's reflections).
    vec3 mirror = reflect(-s.v, s.n);
    vec3 skyReflection;
    if (AtmosphereZenith.w > 0.5) {
        // Bedrock's sky probe is its sky (the atmosphere function), so the water reflects the same sky.
        skyReflection = toDisplay(bedrockSkyReflection(mirror, s.roughness, sunDir, moonDir) * sky);
    } else {
        float horizonShare = 0.3 * (1.0 - clamp(mirror.y * 0.5 + 0.5, 0.0, 1.0));
        skyReflection = toDisplay(mix(SkyColor.rgb, SkyHorizonColor.rgb, horizonShare) * sky);
        // Rougher texels see a blurrier sky; here the reflected sky leans towards its average.
        skyReflection = mix(skyReflection, toDisplay(mix(SkyColor.rgb, SkyHorizonColor.rgb, 0.15) * sky), s.roughness);
    }
    // Bedrock's SSR is mirror-only: full up to roughness 0.3, gone by 0.4.
    float ssrWeight = 1.0 - smoothstep(0.3, 0.4, s.roughness);
    vec4 traced = ssrWeight > 0.0 ? screenSpaceReflection(v_Position + s.n * 0.02, mirror, size, flipY) : vec4(0.0);
    vec3 reflection = mix(skyReflection, traced.rgb, traced.a * ssrWeight);

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
    // Bedrock's water only absorbs (WaterExtinction); light scattered in the water comes from volumetric fog.
    vec3 transmitted = behind * s.transmittance;
    vec3 target = clamp(s.fresnel * reflection + (1.0 - s.fresnel) * transmitted + toDisplay(glint), 0.0, 1.0);
    return clamp((target - (1.0 - s.opacity) * behind) / max(s.opacity, 1e-3), 0.0, 1.0);
}

void main() {
    // Derivatives first, outside any branch: neighbouring pixels may disagree about being water.
    // Flat face normal from screen-space derivatives (turned to face the camera in waterSurface).
    vec3 dpx = dFdx(v_Position);
    vec3 dpy = dFdy(v_Position);
    vec3 faceNormal = normalize(cross(dpx, dpy));
    // Tangent frame from position and UV derivatives: the directions the texture's u and v run on this face.
    vec2 duvx = dFdx(v_TexCoord);
    vec2 duvy = dFdy(v_TexCoord);
    vec3 tangent = normalize(dpx * duvy.y - dpy * duvx.y + vec3(1e-9));
    vec3 bitangent = normalize(dpy * duvx.x - dpx * duvy.x + vec3(1e-9));
    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);

    if (isWater()) {
        WaterSurface surface = waterSurface(faceNormal, tangent, bitangent);
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
