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
#define SKY_PROBE SkyProbe
#include <bedrockvisuals:sky.glsl>
#define LOCAL_EXPOSURE_APPLY
#include <bedrockvisuals:local_exposure.glsl>

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
#include <bedrockvisuals:water_columns.glsl>
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

// How far a reflection found at screen position hit is trusted near the screen's edges, where the scene it
// reflects runs out: faded within 8% of an edge, but sideways only as far as the hit lies sideways of this
// pixel. On a flat mirror the reflection lies almost straight above the water's pixel, so the water along the
// screen's sides lost its reflections though they were on screen.
float screenEdgeFade(vec2 hit, vec2 size) {
    vec2 edge = min(hit, size - hit) / size;
    float drift = smoothstep(0.0, 0.05, abs(hit.x - gl_FragCoord.x) / size.x);
    float sideways = 1.0 - (1.0 - smoothstep(0.0, 0.08, edge.x)) * drift;
    return min(sideways, smoothstep(0.0, 0.08, edge.y));
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
            return vec4(texelFetch(u_SceneColor, ivec2(hit), 0).rgb, screenEdgeFade(hit, size));
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
vec4 distantReflection(vec3 origin, vec3 direction, vec2 size, bool flipY) {
    if (DistantParams.x < 0.5) {
        return vec4(0.0);
    }
    float stepLength = max(length(origin) * 0.01, 1.0);
    float travelled = 0.0;
    for (int i = 0; i < DISTANT_SSR_STEPS; i++) {
        travelled += stepLength;
        stepLength *= 1.15;
        vec3 point = origin + direction * travelled;
        vec3 screen = toScreen(point, size, flipY);
        if (screen.z <= 0.0 || !onScreen(screen.xy, size)) {
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
        if (sceneDistance < 0.0) {
            continue;
        }
        if (rayDistance > sceneDistance && rayDistance - sceneDistance < max(stepLength * 2.0, 1.0)) {
            return vec4(texelFetch(u_SceneColor, pixel, 0).rgb, screenEdgeFade(screen.xy, size));
        }
    }
    return vec4(0.0);
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
        vec3 reflection = mix(probe, traced.rgb * toSurface, traced.a);
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

    // Reflection: the scene where the mirror ray lands (already locally exposed), else the sky gradient
    // (locally exposed here, as the water is drawn after that pass) (with the same horizon handling
    // as the deferred pass's reflections).
    vec3 mirror = reflect(-s.v, s.n);
    vec3 skyReflection;
    if (AtmosphereZenith.w > 0.5) {
        // Bedrock's sky probe is its sky (the atmosphere function), so the water reflects the same sky.
        skyReflection = toDisplay(locallyExposed(bedrockSkyReflection(mirror, s.roughness, sunDir, moonDir) * iblFade));
    } else {
        float horizonShare = 0.3 * (1.0 - clamp(mirror.y * 0.5 + 0.5, 0.0, 1.0));
        skyReflection = toDisplay(locallyExposed(mix(SkyColor.rgb, SkyHorizonColor.rgb, horizonShare) * iblFade));
        // Rougher texels see a blurrier sky; here the reflected sky leans towards its average.
        skyReflection = mix(skyReflection, toDisplay(locallyExposed(mix(SkyColor.rgb, SkyHorizonColor.rgb, 0.15) * iblFade)),
                s.roughness);
    }
    // Bedrock's SSR is mirror-only: full up to roughness 0.3, gone by 0.4.
    float ssrWeight = 1.0 - smoothstep(0.3, 0.4, s.roughness);
    vec4 traced = ssrWeight > 0.0 ? screenSpaceReflection(v_Position + s.n * 0.02, mirror, size, flipY) : vec4(0.0);
    float nearHit = traced.a;
    float farHit = 0.0;
    if (ssrWeight > 0.0 && traced.a < 0.5) {
        vec4 far = distantReflection(v_Position + s.n * 0.02, mirror, size, flipY);
        if (far.a > traced.a) {
            traced = far;
            farHit = far.a;
        }
    }
    vec3 reflection = mix(skyReflection, traced.rgb, traced.a * ssrWeight);
    // The reflection views (the flicker test's): 11 where each reflection comes from (red: the screen-space
    // march's hit, green: the far march's against Distant Horizons' depth, blue: the sky's share), 12 the
    // reflected sky alone, 13 the reflected scene alone. Drawn opaque (waterSurface).
    int reflectionView = int(DebugParams.w + 0.5);
    if (reflectionView == 11) {
        return vec3(nearHit * ssrWeight, farHit * ssrWeight, 0.15 + 0.85 * (1.0 - traced.a * ssrWeight));
    } else if (reflectionView == 12) {
        return skyReflection;
    } else if (reflectionView == 13) {
        return traced.rgb * traced.a * ssrWeight;
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
    // Bedrock's water only absorbs (WaterExtinction); light scattered in the water comes from volumetric fog.
    vec3 transmitted = behind * s.transmittance;
    if (s.scatters) {
        // Beyond the water column map, the water's own scattered light (the lighting pass adds it within).
        transmitted += toDisplay(locallyExposed(waterInScatter(normalize(v_Position), s.path, s.beneathLit,
                // The surface's sky light (one level up for the water block's own dimming of it), as the
                // lighting pass takes its column's.
                min(VolumeCamera.x, s.beneathSky >= 0.0 ? waterSkyAccess(s.beneathSky)
                        : waterSkyAccess(clamp(levels.y + 1.0 / 15.0, 0.0, 1.0))))));
    }
    vec3 surfaceLight = specularScale * reflection + toDisplay(locallyExposed(glint));
    // DeferredWater fogs the surface's own light like terrain: towards the sky radiance (blended to the
    // underground colour where sky light is low) times its alpha F, by the distance fog. What shows through
    // was fogged by the lighting pass already.
    if (AtmosphereZenith.w > 0.5) {
        float fog = bedrockFogAmount(length(v_Position));
        if (fog > 0.0) {
            float open = max(sqrt(VolumeCamera.x) * 15.0 / 16.0, levels.y);
            vec3 fogColor = toDisplay(locallyExposed(
                    bedrockSkyRadiance(normalize(v_Position), sunDir, moonDir) * AtmosphereHorizon.w * open));
            surfaceLight = mix(surfaceLight, fogColor * s.fresnel, fog);
        }
        // Then the volumetric fog between the camera and the surface.
        vec3 fogTransmittance;
        vec3 fogLight = surfaceFog(normalize(v_Position), length(v_Position), fogTransmittance);
        surfaceLight = surfaceLight * fogTransmittance + toDisplay(locallyExposed(fogLight)) * s.fresnel;
    }
    if (abs(DebugParams.w - 5.0) < 0.5) {
        transmitted = vec3(0.0); // the surface view: its reflection and glint alone
    }
    vec3 target = clamp(surfaceLight + (1.0 - s.fresnel) * transmitted, 0.0, 1.0);
    return clamp((target - (1.0 - s.opacity) * behind) / max(s.opacity, 1e-3), 0.0, 1.0);
}

void main() {
    // Derivatives first, outside any branch: neighbouring pixels may disagree about being water.
    // Flat face normal from screen-space derivatives (turned to face the camera in waterSurface).
    vec3 dpx = dFdx(v_Position);
    vec3 dpy = dFdy(v_Position);
    vec4 clipHere = u_ProjectionMatrix * u_ModelViewMatrix * vec4(v_Position, 1.0);
    screenFlipped = dFdy(clipHere.y / clipHere.w) < 0.0;
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
        WaterSurface surface = waterSurface(faceNormal, tangent, bitangent);
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, surface.opacity);
        #else
        // The water texel's grey (untinted: Bedrock's G-buffer gets water_still_grey's colour), linear.
        float albedo = toLinear(vec3(dot(color.rgb, vec3(0.2126, 0.7152, 0.0722)))).r;
        vec3 shaded = abs(DebugParams.w - 6.0) < 0.5 ? vec3(0.95, 0.85, 0.0) : waterColor(surface, albedo);
        fragColor = calculateFinalColor(vec4(shaded, surface.opacity));
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
