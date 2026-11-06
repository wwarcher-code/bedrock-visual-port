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

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>
#define WATER_COLUMNS WaterColumnMap
#include <bedrockvisuals:water_columns.glsl>
#include <bedrockvisuals:froxels.glsl>

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
    return clip.z >= texture(ShadowMap3, uv).r - ShadowCascadeBias[3] ? 1.0 : 0.0;
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
        vec3 stepAmbient = ambient;
        float stepSun = 1.0;
        if (water && p.y < UnderwaterMedium.w) {
            extinction = UnderwaterMedium.rgb;
            scattering = UnderwaterScattering.rgb;
            g = UnderwaterScattering.w;
        } else if (waterBelow && depthBelowWaterSurface(p) >= 0.0) {
            // Seen from above, the water between its surface and the floor (PopulateVolume picks the water
            // medium between the water's front and back depths), dimmed by the water above it along the view
            // at the volume's rate, per point; its ambient light by the sky light reaching that water, and no
            // sun where none reaches.
            extinction = vec3(0.0);
            scattering = UnderwaterScattering.rgb * waterScatterTransmittanceTo(p, dir);
            g = UnderwaterScattering.w;
            float columnSky = max(waterColumnSkyLight(p), 0.0);
            stepAmbient = SkyColor.rgb * waterSkyAccess(columnSky) * 0.07957747;
            stepSun = columnSky > 0.0001 ? 1.0 : 0.0;
        } else {
            float density = clamp(VolumeHeight.x * p.y + VolumeHeight.y, 0.0, 1.0) * VolumeHeight.w;
            extinction = vec3(VolumeAir.w * density);
            scattering = extinction * VolumeAir.rgb;
            g = VolumeHeight.z;
        }
        scattering *= 1.0 - bedrockFogAmount(t);
        float visibility = volumeShadow(p) * cloudShadow(p, l) * stepSun;
        vec3 source = scattering * (stepAmbient + light * phaseHG(g, cosine) * visibility);
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
