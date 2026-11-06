#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's volumetric fog on its coarse grid (PopulateVolume, then VolumeScattering's front-to-back
// integration): one texel per froxel of the atlas (froxels.glsl). Each holds the light scattered towards the
// camera, and the transmittance, from the camera to its slice's centre, stepped slice by slice through the
// medium at each slice's centre: the air with its height density, the water under the camera's surface while
// it is under water, and from above, the water in the water column map (dimmed by the water above it along
// the view). The lighting pass reads it at each pixel's depth; on this coarse grid the water's light blends
// across edges in the picture as Bedrock's does, instead of stepping at every block.

uniform sampler2D ShadowMap3;      // the outer shadow cascade (ShadowPass.java)
uniform sampler2D CloudShadowMap;  // filled cloud cells (CloudShadows.java)
uniform sampler2D WaterColumnMap;  // each column's top water body (WaterColumns.java)
uniform sampler2D LightVolumeMap;  // sky and block light around the camera (LightVolume.java)

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>
#define WATER_COLUMNS WaterColumnMap
#include <bedrockvisuals:water_columns.glsl>
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
    bool waterBelow = !water && WaterColumnOrigin.w > 0.5;
    if (slice >= FROXEL_SLICES || AtmosphereZenith.w < 0.5 || (VolumeHeight.w < 0.5 && !water && !waterBelow)) {
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

        // Seen from above, the water between its surface and the floor is the volume's water medium
        // (PopulateVolume picks it between the water's front and back depths: WaterAlbedoExtinction, 0.112 a
        // block in every channel, integrated here like the air's). What it scatters then passes up through the
        // water above it, which DeferredWater dims by WaterExtinction: applied per point here. The step takes the
        // share of its stretch that lies between the column's surface and bottom (a slice all water or all air
        // by its centre striped the water in bands of constant depth).
        WaterColumn column;
        if (waterBelow && waterColumnAt(p, column)) {
            float surfaceY = column.surface - WaterColumnOrigin.y;
            float bottomY = column.bottom - 0.3 - WaterColumnOrigin.y;
            float y0 = dir.y * t0;
            float y1 = dir.y * t1;
            float lo = min(y0, y1);
            float hi = max(y0, y1);
            float share = hi - lo < 1e-3 ? (lo < surfaceY && lo >= bottomY ? 1.0 : 0.0)
                    : clamp((min(hi, surfaceY) - max(lo, bottomY)) / (hi - lo), 0.0, 1.0);
            if (share > 0.0) {
                // Its light at a point of the stretch inside the water.
                vec3 inWater = vec3(p.x, clamp(p.y, bottomY + 0.05, surfaceY - 0.05), p.z);
                // The sky light there where the light volume reaches (water under a ledge or an overhang is dark
                // by its own light), else its column's. Minecraft takes a level of sky light per block of water
                // above a point; Bedrock's sky samples don't (the water medium does the dimming), so that loss is
                // given back, up to what reaches the column's surface.
                float surfaceSky = max(waterColumnSkyLight(inWater), 0.0);
                float depth = max(surfaceY - inWater.y, 0.0);
                float columnSky = here.x >= 0.0 ? min(here.x + depth / 15.0, max(surfaceSky, here.x)) : surfaceSky;
                vec3 waterAmbient = (SkyColor.rgb * waterSkyAccess(columnSky)
                        + BlockLightColor.rgb * blockLightColor(max(here.y, 0.0))) * 0.07957747;
                float waterSun = columnSky > 0.0001 ? 1.0 : 0.0;
                vec3 waterScattering = UnderwaterScattering.rgb
                        * exp(-UnderwaterMedium.rgb * min(depth / max(-dir.y, 0.05), 64.0));
                vec3 waterSource = waterScattering * (1.0 - bedrockFogAmount(t)) * (waterAmbient
                        + light * phaseHG(UnderwaterScattering.w, cosine) * volumeShadow(inWater)
                        * cloudShadow(inWater, l) * waterSun);
                extinction = mix(extinction, vec3(WATER_VOLUME_EXTINCTION), share);
                source = mix(source, waterSource, share);
            }
        }
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
