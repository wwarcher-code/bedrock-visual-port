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

// The lighting pass's DistantData block (shared with the clouds), for FroxelParams: whether this frame's fog grid
// is there.
layout(std140) uniform DistantData {
    mat4 DistantInverseProjection;
    vec4 DistantParams;
    vec4 DistantLightmap;
    vec4 DistantHeightOrigin;
    vec4 FroxelParams;
};
// The ray-traced light for entities (raytrace/EntityProbes, render/LightingPass's RtEntityData): probes of the
// light from emissive texels, one a block around the camera, as ambient cubes, and the lights entities carry.
uniform sampler2D RtProbeMap;
layout(std140) uniform RtEntityData {
    vec4 RtProbeCorner;  // xyz: the probe grid's lowest corner relative to the camera; w: 1 when the emitted
                         // light is on this frame
    vec4 RtEntityParams; // x: the share of Bedrock's block light kept under it; y: lights carried; z: the gain;
                         // w: 1 when the bounced light is on this frame
    vec4 RtCarried[64];  // per light: position relative to the camera and radius; colour x strength, reach
};
const ivec3 RT_PROBES = ivec3(48, 24, 48); // EntityProbes.X, Y, Z

// One probe's light on a surface facing n (its ambient cube: the emitted light's at face 0, the bounced light's
// at 6), or alpha 0 for a probe inside a solid block.
vec4 rtProbe(ivec3 cell, vec3 n, int face) {
    ivec2 at = ivec2(cell.x * 12 + face, cell.z + cell.y * RT_PROBES.z);
    vec4 x = texelFetch(RtProbeMap, at + ivec2(n.x >= 0.0 ? 0 : 1, 0), 0);
    vec4 y = texelFetch(RtProbeMap, at + ivec2(n.y >= 0.0 ? 2 : 3, 0), 0);
    vec4 z = texelFetch(RtProbeMap, at + ivec2(n.z >= 0.0 ? 4 : 5, 0), 0);
    return vec4(x.rgb * n.x * n.x + y.rgb * n.y * n.y + z.rgb * n.z * n.z, x.a);
}

// The probes' light at the camera-relative point p on a surface facing n (face 0: emitted, 6: bounced): the
// eight probes around it, blended by distance, leaving out those in solid blocks. Negative outside the grid.
vec3 rtProbes(vec3 p, vec3 n, int face) {
    vec3 q = p + n * 0.3 - RtProbeCorner.xyz - 0.5; // probe centres at whole numbers
    ivec3 base = ivec3(floor(q));
    if (any(lessThan(base, ivec3(0))) || any(greaterThanEqual(base + 1, RT_PROBES))) {
        return vec3(-1.0);
    }
    vec3 f = q - vec3(base);
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 corner = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w3 = mix(1.0 - f, f, vec3(corner));
        float w = w3.x * w3.y * w3.z;
        vec4 probe = rtProbe(base + corner, n, face);
        sum += probe.rgb * w * probe.a;
        weight += w * probe.a;
    }
    return weight > 1e-3 ? sum / weight : vec3(0.0);
}

// The traced light at the camera-relative point p on a surface facing n: the probes' emitted light, then the
// lights entities carry, unshadowed. Negative outside the grid (Bedrock's block light then).
vec3 rtEntityLight(vec3 p, vec3 n) {
    vec3 light = rtProbes(p, n, 0);
    if (light.x < 0.0) {
        return light;
    }
    int carried = int(RtEntityParams.y);
    for (int i = 0; i < carried; i++) {
        vec4 at = RtCarried[i * 2];
        vec4 strength = RtCarried[i * 2 + 1];
        vec3 toLight = at.xyz - p;
        float d2 = dot(toLight, toLight);
        if (d2 < strength.w * strength.w) {
            float fade = 1.0 - d2 / (strength.w * strength.w);
            float cosine = max(dot(n, toLight) * inversesqrt(max(d2, 1e-6)), 0.0);
            light += strength.rgb * cosine * fade * fade / max(d2, 0.0625) * RtEntityParams.z / 3.14159265;
        }
    }
    return light;
}

uniform sampler2D FroxelScatter;      // Bedrock's fog grid (FroxelVolume): in-scattered light to each slice
uniform sampler2D FroxelTransmittance; // and the transmittance
#include <bedrockvisuals:froxels.glsl>

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
layout(location = 12) in vec4 bvClip;

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

// Bedrock's texel quantization (common.glsl, snapToTexel), for entities: the point the sun and moon are tested
// at, moved within the face to the centre of its texel of the entity's own texture (entity models aren't on
// the block grid). From the screen-space derivatives of the position and texture coordinates, taken in main
// before any discard; on a flat face they hold exactly. Shadow edges then follow the model's pixels instead of
// cutting across them. Left where the face is seen edge-on or the move would be more than a quarter block.
vec3 bvDpx;
vec3 bvDpy;
vec2 bvDux;
vec2 bvDuy;

vec3 bvSnapToTexel(vec3 p, vec2 uv) {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 dux = bvDux * size;
    vec2 duy = bvDuy * size;
    float det = dux.x * duy.y - duy.x * dux.y;
    if (abs(det) < 1e-6) {
        return p;
    }
    vec2 texel = uv * size;
    vec2 toCentre = floor(texel) + 0.5 - texel;
    // The screen offset s with dux × s.x + duy × s.y = toCentre.
    vec2 s = vec2(duy.y * toCentre.x - duy.x * toCentre.y, dux.x * toCentre.y - dux.y * toCentre.x) / det;
    vec3 offset = bvDpx * s.x + bvDpy * s.y;
    return dot(offset, offset) < 0.0625 ? p + offset : p;
}

// The face's own normal (flat across it, unlike the interpolated one), turned to the side n is on.
vec3 bvFaceNormal(vec3 n) {
    vec3 face = cross(bvDpx, bvDpy);
    float length2 = dot(face, face);
    if (length2 < 1e-20) {
        return n;
    }
    face *= inversesqrt(length2);
    return dot(face, n) < 0.0 ? -face : face;
}

// The darkest of the cascades the point falls in, faded out over the shadow range's last 8 blocks; -1 when
// there's no shadow map or the point is in none of them.
float bvShadow(vec3 p, vec3 n, vec3 towardsLight) {
    if (ShadowParams.x < 0.5) {
        return -1.0;
    }
    float grazing = clamp(1.0 - dot(n, towardsLight), 0.0, 1.0);
    // Plus a texel of the shadow map's finest cascade (1/48 block) out along the face: the model's own faces
    // are in the map too, off the terrain's block grid, and with the terrain's bias alone they shaded
    // themselves in speckles.
    vec3 q = p + n * (ShadowParams.z * grazing + 0.02);
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
    vec3 face = bvFaceNormal(n);
    float mapped = bvShadow(bvSnapToTexel(bvPosition, texCoord0), face, moonCasts ? moonDir : sunDir);
    float casterVisibility = mapped < 0.0 ? skyFallback : mapped * skyGuard;
    float sunVisibility = moonCasts ? skyFallback : casterVisibility;
    float moonVisibility = moonCasts ? casterVisibility : skyFallback;
    vec3 color = albedo / 3.14159265 * (sunLight() * sunVisibility * max(dot(n, sunDir), 0.0)
            + moonLight() * moonVisibility * max(dot(n, moonDir), 0.0));
    float cameraSky = sqrt(VolumeCamera.x) * 15.0 / 16.0;
    float skyCurve = skyLight * mix(pow(skyLight, 4.0), skyLight * skyLight, cameraSky);
    vec3 blockTerm = BlockLightColor.rgb * blockLightColor(blockLight);
    // The ray-traced light, when on: in place of Bedrock's block light, as for the terrain (deferred.fsh), or
    // over the given share of it.
    if (RtProbeCorner.w > 0.5) {
        vec3 traced = rtEntityLight(bvPosition, n);
        if (traced.x >= 0.0) {
            blockTerm = max(traced, blockTerm * RtEntityParams.x);
        }
    }
    vec3 indirect = max(blockTerm + SkyColor.rgb * skyCurve, AmbientColor.rgb);
    // The bounced light (the sun, moon and emitted light off the surfaces around), over Bedrock's terms as the
    // terrain has it (deferred.fsh).
    if (RtEntityParams.w > 0.5) {
        indirect += max(rtProbes(bvPosition, n, 6), vec3(0.0));
    }
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
    // Bedrock's volumetric fog between the camera and the entity, from the same grid the terrain reads
    // (lighting/deferred.fsh, fogTo): it dims the entity and adds the light the air scatters in front of it.
    // Seen from above under mapped water, the grid is read down to the water's surface, the water's own share
    // being added above.
    if (FroxelParams.x > 0.5) {
        vec2 uv = bvClip.xy / bvClip.w * 0.5 + 0.5;
        vec3 forward = froxelForward(InverseViewProjection);
        vec3 through = bvPosition;
        if (UnderwaterParams.x < 0.5 && dir.y < -1e-3) {
            float depth = depthBelowWaterSurface(waterTestPoint(bvPosition));
            if (depth >= 0.0) {
                through = dir * ((bvPosition.y + depth) / dir.y);
            }
        }
        vec3 fogTransmittance;
        vec3 fogLight = froxelFog(FroxelScatter, FroxelTransmittance, uv, max(dot(through, forward), 0.0),
                fogTransmittance);
        color = color * fogTransmittance + fogLight;
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
    bvDpx = dFdx(bvPosition);
    bvDpy = dFdy(bvPosition);
    bvDux = dFdx(texCoord0);
    bvDuy = dFdy(texCoord0);
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
