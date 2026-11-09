#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's volumetric fog on its coarse grid (PopulateVolume, then VolumeScattering's front-to-back
// integration): one texel per froxel of the atlas (froxels.glsl). Each holds the light scattered towards the
// camera, and the transmittance, from the camera to its slice's centre, stepped slice by slice through the
// medium at each slice's centre: the air with its height density, and the water under the camera's surface
// while it is under water. The lighting pass reads it at each pixel's depth. Water seen from above isn't in
// it: the lighting pass reads the air down to the surface here and integrates the water along each pixel's
// own path (waterHaze in lighting/deferred.fsh); on this grid its haze came in cells of 8 x 8 pixels.

uniform sampler2D ShadowMap3;      // the outer shadow cascade (ShadowPass.java)
uniform sampler2D CloudShadowMap;  // filled cloud cells (CloudShadows.java)
uniform sampler2D LightVolumeMap;  // sky and block light around the camera (LightVolume.java)

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>
#include <bedrockvisuals:froxels.glsl>
#define LIGHT_VOLUME LightVolumeMap
#include <bedrockvisuals:light_volume.glsl>

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 scatterOut;
layout(location = 1) out vec4 transmittanceOut;

float phaseHG(float g, float cosine) {
    return (1.0 - g * g) * 0.07957747 / pow(max(1.0 + g * (2.0 * cosine + g), 1e-4), 1.5);
}

// The outer cascade's test at p (the fog reaches past the inner ones), as the water pass's surface fog does.
float volumeShadow(vec3 p) {
    if (ShadowParams.x < 0.5) {
        return 1.0;
    }
    vec4 clip = ShadowViewProjection[3] * vec4(p, 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return 1.0;
    }
    // Four taps a texel apart, averaged: one hard test per froxel made light shafts streak.
    float texel = ShadowParams.y;
    float lit = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 offset = (vec2(float(i & 1), float(i >> 1)) - 0.5) * texel;
        lit += clip.z >= texture(ShadowMap3, uv + offset).r - ShadowCascadeBias[3] ? 1.0 : 0.0;
    }
    return lit * 0.25;
}

float cloudShadow(vec3 p, vec3 towardsLight) {
    if (CloudShadowField.w <= 0.0 || towardsLight.y <= 0.01) {
        return 1.0;
    }
    float rise = CloudShadowField.z - p.y;
    if (rise <= 0.0) {
        return 1.0;
    }
    vec2 field = (CloudShadowField.xy + p.xz + towardsLight.xz * (rise / towardsLight.y)) / CloudShadowSize.z;
    float filled = texture(CloudShadowMap, (floor(field) + 0.5) / CloudShadowSize.xy).r;
    return filled > 0.5 ? 1.0 - CloudShadowField.w : 1.0;
}

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 tile = pixel / FROXEL_CELLS;
    int slice = tile.y * FROXEL_TILES_X + tile.x;
    scatterOut = vec4(0.0, 0.0, 0.0, 1.0);
    transmittanceOut = vec4(1.0);
    bool water = UnderwaterParams.x > 0.5;
    if (slice >= FROXEL_SLICES || AtmosphereZenith.w < 0.5 || (VolumeHeight.w < 0.5 && !water)) {
        return;
    }
    vec2 cellUv = (vec2(pixel - tile * FROXEL_CELLS) + 0.5) / vec2(FROXEL_CELLS);
    vec4 onRay = InverseViewProjection * vec4(cellUv * 2.0 - 1.0, 0.5, 1.0);
    vec3 dir = normalize(onRay.xyz / onRay.w);
    // Slices are planes of view depth: along this ray, a depth z lies z / cos from the camera.
    float along = max(dot(dir, froxelForward(InverseViewProjection)), 0.05);

    bool moonCasts = ShadowParams.w > 0.5;
    vec3 l = normalize(moonCasts ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moonCasts ? MoonColor.rgb : SunColor.rgb;
    float cosine = dot(l, -dir);
    vec3 ambient = SkyColor.rgb * VolumeCamera.x * 0.07957747;
    vec3 inScattered = vec3(0.0);
    vec3 transmittance = vec3(1.0);
    for (int j = 0; j <= slice; j++) {
        float t0 = froxelDepth(float(j) / float(FROXEL_SLICES)) / along;
        // The cell's own slice up to its centre, the ones before it whole.
        float t1 = froxelDepth((float(j) + (j == slice ? 0.5 : 1.0)) / float(FROXEL_SLICES)) / along;
        float stepLength = t1 - t0;
        float t = 0.5 * (t0 + t1);
        vec3 p = dir * t;
        vec3 extinction;
        vec3 scattering;
        float g;
        // The ambient light at this point (PopulateVolume: sky ambient times the sky light there, plus the block
        // light's base ambient times the block light there, from its sky samples), and no sun where no sky
        // light reaches. Beyond the light volume, the camera's sky access as before.
        vec3 stepAmbient = ambient;
        float stepSun = 1.0;
        vec2 here = lightVolumeAt(p);
        if (here.x >= 0.0) {
            stepAmbient = (SkyColor.rgb * here.x * here.x + BlockLightColor.rgb * blockLightColor(here.y)) * 0.07957747;
            stepSun = here.x > 0.0001 ? 1.0 : 0.0;
            // As on the surfaces (deferred.fsh): where the sky light isn't full, the blocks towards the light
            // stand in for casters missing from the shadow map (Sodium's cave culling), or caves' air glowed.
            if (stepSun > 0.0 && here.x < 0.999) {
                stepSun *= voxelRayVisibility(p, l, 24);
            }
        }
        if (water && p.y < UnderwaterMedium.w) {
            extinction = UnderwaterMedium.rgb;
            scattering = UnderwaterScattering.rgb;
            g = UnderwaterScattering.w;
            if (here.x >= 0.0) {
                // As from above: the level per block of water above the point that Minecraft took is given back.
                float sky = min(here.x + (UnderwaterMedium.w - p.y) / 15.0, 1.0);
                stepAmbient = (SkyColor.rgb * sky * sky + BlockLightColor.rgb * blockLightColor(here.y)) * 0.07957747;
                stepSun = sky > 0.0001 ? 1.0 : 0.0;
            }
        } else {
            float density = clamp(VolumeHeight.x * p.y + VolumeHeight.y, 0.0, 1.0) * VolumeHeight.w;
            extinction = vec3(VolumeAir.w * density);
            scattering = extinction * VolumeAir.rgb;
            g = VolumeHeight.z;
        }
        vec3 source = scattering * (1.0 - bedrockFogAmount(t))
                * (stepAmbient + light * phaseHG(g, cosine) * volumeShadow(p) * cloudShadow(p, l) * stepSun);

        vec3 stepTransmittance = exp(-extinction * stepLength);
        vec3 stepIntegral = vec3(
                extinction.r > 1e-5 ? (1.0 - stepTransmittance.r) / extinction.r : stepLength,
                extinction.g > 1e-5 ? (1.0 - stepTransmittance.g) / extinction.g : stepLength,
                extinction.b > 1e-5 ? (1.0 - stepTransmittance.b) / extinction.b : stepLength);
        inScattered += transmittance * source * stepIntegral;
        transmittance *= stepTransmittance;
    }
    scatterOut = vec4(inScattered, 1.0);
    transmittanceOut = vec4(transmittance, 1.0);
}
