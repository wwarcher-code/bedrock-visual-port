#version 330
#extension GL_ARB_separate_shader_objects : require

// Deferred lighting: turns the G-buffer into lit color. Terrain only. Within the shadow map's range the sun
// (or moon) is tested against it; beyond it, sky light stands in for sun visibility.

uniform sampler2D Albedo;
uniform sampler2D Normal;
uniform sampler2D Material;
uniform sampler2D Light;
uniform sampler2D Depth;
uniform sampler2D ShadowMap0;    // Bedrock's four shadow cascades (ShadowPass.java), nearest first
uniform sampler2D ShadowMap1;
uniform sampler2D ShadowMap2;
uniform sampler2D ShadowMap3;
uniform sampler2D CloudShadowMap;  // filled cloud cells (CloudShadows.java)
uniform sampler2D WaterColumnMap; // each column's top water body (WaterColumns.java)
uniform sampler2D CausticsMap;     // Bedrock's renderer/caustics.png: frames of 32x32 stacked vertically
uniform sampler2D BrdfLut;         // Bedrock's renderer/brdf_lut.png (BrdfLut.java)
uniform sampler2D CloudCoverage;   // the cloud cells' share per area, mipmapped (CloudShadows.java)
uniform sampler2D DistantColor;    // Distant Horizons' colour and depth this frame (DistantTerrain)
uniform sampler2D DistantDepth;      // with DistantParams.w: DH's depth before its water (the ground)
uniform sampler2D DistantWaterDepth; // DH's final depth, its water surface included
uniform sampler2D PreviousScene;   // the previous frame's HDR scene (BloomPass.java), for reflections

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>
#define WATER_COLUMNS WaterColumnMap
#include <bedrockvisuals:water_columns.glsl>
layout(std140) uniform DistantData {
    mat4 DistantInverseProjection; // Distant Horizons' clip space to its view space
    vec4 DistantParams;            // x: its terrain is lit here, y: reversed depth, z: [0, 1] clip depth,
                                   // w: DistantDepth is the ground under DH's water (DistantHorizonsAdapter)
    vec4 DistantLightmap;          // rgb: Minecraft's lightmap at full sky light, which DH multiplied in
};

#define CLOUD_CELLS CloudShadowMap
#define CLOUD_BRDF BrdfLut
#include <bedrockvisuals:cloud_lighting.glsl>


layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
// Pre-exposure luminance, for the auto exposure's histogram (LuminanceMeter.java).
layout(location = 1) out vec4 meterLuminance;
// The HDR colour before exposure, for bloom (BloomPass.java); alpha 0 where Java's own sky is kept.
layout(location = 2) out vec4 hdrColor;


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
    // Bedrock's direct lighting: Lambert (albedo / π) for the diffuse part.
    return ((1.0 - fresnel) * diffuseColor / PI + specular) * radiance * nDotL;
}

// One cascade's test: 1 lit, 0 shadowed, -1 when the point is outside it. Depth is reversed in the maps: larger
// values are nearer the light, so a point is lit when nothing nearer the light was stored.
float cascadeTest(sampler2D map, int cascade, vec3 p, float slope) {
    vec4 clip = ShadowViewProjection[cascade] * vec4(p, 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return -1.0;
    }
    // One tap: with the lookup snapped to the texel grid, Bedrock's shadow edges are crisp per texel.
    // Bedrock's bias: depth bias plus tan(acos(max(N·L, NdLFloor))) times the slope bias.
    float bias = ShadowCascadeBias[cascade] + slope * ShadowCascadeSlope[cascade];
    return clip.z >= texture(map, uv).r - bias ? 1.0 : 0.0;
}

// The nearest cascade a point falls in, or -1 (for the cascade debug view).
int nearestCascade(vec3 p) {
    for (int c = 0; c < 4; c++) {
        vec4 clip = ShadowViewProjection[c] * vec4(p, 1.0);
        vec2 uv = clip.xy * 0.5 + 0.5;
        if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0))) && clip.z > 0.0 && clip.z < 1.0) {
            return c;
        }
    }
    return -1;
}

// Bedrock's cascaded shadow test: the minimum over every cascade the point falls in, or -1 outside them all.
// edgeFade is 1 inside the range and falls to 0 over its last min(range × 0.1, 8) blocks, where Bedrock fades
// shadows out.
float shadowVisibility(vec3 position, vec3 n, vec3 towardsLight, out float edgeFade) {
    edgeFade = 0.0;
    if (ShadowParams.x < 0.5) {
        return -1.0;
    }
    // Bedrock's normal offset: out along the normal by normal_offset_strength x (1 - N.L) blocks.
    float grazing = clamp(1.0 - dot(n, towardsLight), 0.0, 1.0);
    vec3 p = position + n * ShadowParams.z * grazing;
    // NdLFloor 0.04 (captured) caps the slope term at tan(acos(0.04)), about 25.
    float nDotL = max(dot(n, towardsLight), 0.04);
    float slope = sqrt(1.0 - nDotL * nDotL) / nDotL;
    float visibility = 2.0;
    float t;
    t = cascadeTest(ShadowMap0, 0, p, slope); if (t >= 0.0) visibility = min(visibility, t);
    t = cascadeTest(ShadowMap1, 1, p, slope); if (t >= 0.0) visibility = min(visibility, t);
    t = cascadeTest(ShadowMap2, 2, p, slope); if (t >= 0.0) visibility = min(visibility, t);
    t = cascadeTest(ShadowMap3, 3, p, slope); if (t >= 0.0) visibility = min(visibility, t);
    if (visibility > 1.5) {
        return -1.0;
    }
    const float RANGE = 128.0;
    float fade = min(RANGE * 0.1, 8.0);
    edgeFade = 1.0 - smoothstep(RANGE - fade, RANGE, length(position.xz));
    return visibility;
}

// How much of the light the clouds let through at this point: the point is projected along the light onto the
// middle of the cloud layer; under a filled cell it keeps 1 - cloud_shadow_contribution of the light.
float cloudShadow(vec3 position, vec3 towardsLight) {
    if (CloudShadowField.w <= 0.0 || towardsLight.y <= 0.01) {
        return 1.0;
    }
    float rise = CloudShadowField.z - position.y;
    if (rise <= 0.0) {
        return 1.0; // above the clouds
    }
    vec2 onLayer = position.xz + towardsLight.xz * (rise / towardsLight.y);
    vec2 field = (CloudShadowField.xy + onLayer) / CloudShadowSize.z;
    float filled = texture(CloudShadowMap, (floor(field) + 0.5) / CloudShadowSize.xy).r;
    // Bedrock: a shadowed point keeps at least 1 - contribution of the light.
    return filled > 0.5 ? 1.0 - CloudShadowField.w : 1.0;
}

// Bedrock's volumetric fog (PopulateVolume + VolumeScattering), marched along the view instead of through a
// froxel grid: per step, the medium (water below the surface while the camera is under water, else air with
// its height density), in-scattered sky ambient (1/4π) and sun or moon light through the shadow map and the
// cloud shadows (Henyey-Greenstein), integrated front to back. Out to 250 blocks (volumetric_fog
// far_plane), and faded out where the distance fog takes over, as Bedrock does.
const int VOLUME_STEPS = 48;
const float VOLUME_FAR = 250.0;

// Whether the camera-relative point p is in the top water body of its column (WaterColumns.java). It stands
// in for Bedrock's water front and back depths with the camera above water.
bool inWaterColumn(vec3 p) {
    return depthBelowWaterSurface(p) >= 0.0;
}

float phaseHG(float g, float cosine) {
    return (1.0 - g * g) * 0.07957747 / pow(max(1.0 + g * (2.0 * cosine + g), 1e-4), 1.5);
}

float volumeShadow(vec3 p, vec3 towardsLight) {
    if (ShadowParams.x < 0.5) {
        return 1.0;
    }
    // The outer cascade: the fog reaches past the inner ones.
    float t = cascadeTest(ShadowMap3, 3, p, 0.0);
    return t < 0.0 ? 1.0 : t;
}

// rgb: in-scattered light (this renderer's units, before exposure); transmittance in the out parameter.
vec3 volumetricFog(vec3 dir, float distance, out vec3 transmittance) {
    transmittance = vec3(1.0);
    bool water = UnderwaterParams.x > 0.5;
    bool waterBelow = !water && WaterColumnOrigin.w > 0.5;
    if (AtmosphereZenith.w < 0.5 || (VolumeHeight.w < 0.5 && !water && !waterBelow)) {
        return vec3(0.0);
    }
    float end = min(distance, VOLUME_FAR);
    float stepLength = end / float(VOLUME_STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    bool moonCasts = ShadowParams.w > 0.5;
    vec3 l = normalize(moonCasts ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moonCasts ? MoonColor.rgb : SunColor.rgb;
    float cosine = dot(l, -dir);
    vec3 ambient = SkyColor.rgb * VolumeCamera.x * 0.07957747;
    vec3 inScattered = vec3(0.0);
    for (int i = 0; i < VOLUME_STEPS; i++) {
        float t = (float(i) + jitter) * stepLength;
        vec3 p = dir * t;
        vec3 extinction;
        vec3 scattering;
        float g;
        if (water && p.y < UnderwaterMedium.w) {
            extinction = UnderwaterMedium.rgb;
            scattering = UnderwaterScattering.rgb;
            g = UnderwaterScattering.w;
        } else if (waterBelow && inWaterColumn(p)) {
            // Seen from above, the water between its surface and the floor scatters too (Bedrock's
            // PopulateVolume picks the water medium between the water's front and back depths), dimmed by the
            // water above it along the view. That extinction is applied per point (waterTransmittanceTo) rather
            // than accumulated here, so shallow water seen from far doesn't depend on the step length.
            extinction = vec3(0.0);
            scattering = UnderwaterScattering.rgb * waterTransmittanceTo(p, dir);
            g = UnderwaterScattering.w;
        } else {
            float density = clamp(VolumeHeight.x * p.y + VolumeHeight.y, 0.0, 1.0) * VolumeHeight.w;
            extinction = vec3(VolumeAir.w * density);
            scattering = extinction * VolumeAir.rgb;
            g = VolumeHeight.z;
        }
        scattering *= 1.0 - bedrockFogAmount(t);
        float visibility = volumeShadow(p, l) * cloudShadow(p, l);
        vec3 source = scattering * (ambient + light * phaseHG(g, cosine) * visibility);
        vec3 stepTransmittance = exp(-extinction * stepLength);
        // ∫ e^(-σt) dt over the step: (1 - e^(-σ step)) / σ, which tends to the step length as σ goes to 0
        // (the water from above, whose extinction waterTransmittanceTo applies per point instead; clamping σ
        // there made the water's in-scattered light vanish).
        vec3 stepIntegral = vec3(
                extinction.r > 1e-5 ? (1.0 - stepTransmittance.r) / extinction.r : stepLength,
                extinction.g > 1e-5 ? (1.0 - stepTransmittance.g) / extinction.g : stepLength,
                extinction.b > 1e-5 ? (1.0 - stepTransmittance.b) / extinction.b : stepLength);
        inScattered += transmittance * source * stepIntegral;
        transmittance *= stepTransmittance;
    }
    return inScattered;
}

// Bedrock's screen-space reflections (ScreenSpaceReflections; reflection_configuration.pc.json: on below
// roughness 0.4, fading from 0.3), for every smooth surface, not only water. The mirror ray is marched through
// the G-buffer's depth; a hit takes the previous frame's HDR colour there. rgb: colour, a: confidence.
const int SSR_STEPS = 48;
vec4 terrainReflection(vec3 origin, vec3 direction) {
    float stepLength = 0.2;
    float travelled = 0.0;
    for (int i = 0; i < SSR_STEPS; i++) {
        travelled += stepLength;
        stepLength *= 1.12;
        vec3 p = origin + direction * travelled;
        vec4 clip = ViewProjection * vec4(p, 1.0);
        if (clip.w <= 0.0) {
            return vec4(0.0);
        }
        vec3 ndc = clip.xyz / clip.w;
        vec2 uv = ndc.xy * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
            return vec4(0.0);
        }
        float sceneDepth = texture(Depth, uv).r;
        if (sceneDepth <= 0.0) {
            continue;
        }
        vec4 scene = InverseViewProjection * vec4(ndc.xy, sceneDepth, 1.0);
        float sceneDistance = length(scene.xyz / scene.w);
        float rayDistance = length(p);
        if (rayDistance > sceneDistance && rayDistance - sceneDistance < max(stepLength * 2.0, 0.5)) {
            // Fade near the screen's edges (fading_distance), where the reflected scene runs out.
            vec2 edge = min(uv, 1.0 - uv);
            float fade = smoothstep(0.0, 0.1, min(edge.x, edge.y));
            return vec4(texture(PreviousScene, uv).rgb, fade);
        }
    }
    return vec4(0.0);
}

// Bedrock's caustics multiplier (DeferredMixedResolutionCaustics), on direct light: on every surface while the
// camera is under water; from above, on what's under water (the point is nudged off its surface into the
// water, so a pool's walls count too). The sheet repeats every 1/scale blocks across world XZ.
float causticsMultiplier(vec3 position, vec3 n) {
    if (UnderwaterParams.y < 0.0) {
        return 1.0;
    }
    if (UnderwaterParams.x < 0.5 && !inWaterColumn(position + n * 0.05)) {
        return 1.0;
    }
    vec2 world = (position.xz + CameraGridOffset.xz) * UnderwaterParams.z;
    vec2 size = vec2(textureSize(CausticsMap, 0));
    float frames = max(floor(size.y / size.x), 1.0);
    vec2 uv = vec2(fract(world.x), (UnderwaterParams.y + fract(world.y)) / frames);
    float pattern = texture(CausticsMap, uv).r * 2.0 * clamp(n.y, 0.0, 1.0);
    float power = UnderwaterParams.w;
    return pow(max(pattern, 1e-6), power) * (power + 1.0);
}

// Java builds its cloud mesh from the cells within ceil(range / 12) cells of the camera's cell; every point
// closer than about 1.5 cells inside that is always covered (clouds/clouds.fsh fades the mesh out over the last
// quarter of that radius).
float cloudMeshEdge() {
    if (FarClouds.z > 0.0) {
        return FarClouds.z; // the handover (CloudShadows.handover)
    }
    float cells = ceil(max(FarClouds.x, 1.0) / CLOUD_CELL);
    return max((cells - 1.5) * CLOUD_CELL, CLOUD_CELL);
}

// Bedrock's clouds beyond Java's mesh: the view ray is traced through the cloud field (the same cells Java's
// mesh is built from, as they drift) out to where Bedrock's cloud fog, saturate(distance / DistanceControl.x
// - 0.9), has blended them into the sky. They fade in exactly where the mesh fades out, and are lit like the
// mesh's faces (cloud_lighting.glsl). Near, the first filled cell the ray meets is found exactly; far, where
// a pixel spans many cells (one hit per pixel would sparkle), the share of cloud along the ray's path through
// the layer is read from the filtered coverage instead. rgb: colour (this renderer's units), a: coverage;
// nothing past maxDistance. footprint: the angle one pixel spans.
const int FAR_CLOUD_STEPS = 64;

struct FarCloudRay {
    vec3 origin;  // the camera in the field (x, z in blocks; y from the layer's bottom)
    float start;  // where the far clouds begin along the ray
    float end;    // and end (the layer, maxDistance, or where the fog has made them sky)
    float leave;  // where the ray leaves the layer
    float edge;   // the mesh's edge, horizontally
    float far;    // Bedrock's cloud distance
    float horizontal;
};

vec3 farCloudShade(vec3 dir, vec3 n, float t, float viewPath, float lightPath, float far) {
    vec3 color = cloudLight(dir, n, viewPath, lightPath);
    float fog = clamp(t / far - 0.9, 0.0, 1.0);
    return mix(color, bedrockSkyRadiance(dir, normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
            * AtmosphereHorizon.w, fog);
}

vec4 farCloudsTraced(vec3 dir, FarCloudRay r) {
    vec3 p = r.origin + dir * r.start;
    ivec2 cell = ivec2(floor(p.xz / CLOUD_CELL));
    vec2 d = dir.xz;
    ivec2 stepDir = ivec2(sign(d));
    vec2 safe = vec2(abs(d.x) > 1e-6 ? d.x : 1e-6, abs(d.y) > 1e-6 ? d.y : 1e-6);
    vec2 tDelta = abs(vec2(CLOUD_CELL) / safe);
    vec2 tNext = r.start + ((vec2(cell) + step(0.0, d)) * CLOUD_CELL - p.xz) / safe;
    tNext = mix(vec2(1e9), tNext, greaterThan(abs(d), vec2(1e-6)));
    float t = r.start;
    // The face the ray enters by: the layer's top or bottom at first, then the side of the last cell edge.
    vec3 n = vec3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0);
    bool moonLights = ShadowParams.w > 0.5;
    vec3 l = normalize(moonLights ? MoonDirection.xyz : SunDirection.xyz);
    for (int i = 0; i < FAR_CLOUD_STEPS; i++) {
        if (t >= r.end) {
            return vec4(0.0);
        }
        if (cellFilled(cell)) {
            vec3 hit = r.origin + dir * t;
            float viewPath = cloudPath(hit + dir * 0.01, dir);
            float lightPath = cloudPath(hit + dir * (viewPath * 0.5 + 0.01), l);
            float fadeIn = smoothstep(0.75 * r.edge, r.edge, t * r.horizontal);
            return vec4(farCloudShade(dir, n, t, viewPath, lightPath, r.far), CLOUD_OPACITY * fadeIn);
        }
        if (tNext.x < tNext.y) {
            t = tNext.x;
            tNext.x += tDelta.x;
            cell.x += stepDir.x;
            n = vec3(-float(stepDir.x), 0.0, 0.0);
        } else {
            t = tNext.y;
            tNext.y += tDelta.y;
            cell.y += stepDir.y;
            n = vec3(0.0, 0.0, -float(stepDir.y));
        }
    }
    return vec4(0.0);
}

vec4 farCloudsFiltered(vec3 dir, FarCloudRay r, float cellsPerPixel) {
    // The ray's path through the layer, as far as the far clouds go, and the cloud's share around its middle
    // at the pixel's footprint. A path crossing several cells meets cloud more often than one point does.
    float through = max(min(r.leave, r.end) - r.start, 0.0);
    float t = r.start + 0.5 * through;
    vec2 size = CloudShadowSize.xy;
    vec3 p = r.origin + dir * t;
    float lod = log2(max(cellsPerPixel, 1.0));
    float share = textureLod(CloudCoverage, p.xz / (CLOUD_CELL * size), lod).r;
    float cellsCrossed = through * r.horizontal / CLOUD_CELL;
    float hit = 1.0 - pow(1.0 - clamp(share, 0.0, 0.999), 1.0 + cellsCrossed);
    if (hit <= 0.0) {
        return vec4(0.0);
    }
    bool moonLights = ShadowParams.w > 0.5;
    vec3 l = normalize(moonLights ? MoonDirection.xyz : SunDirection.xyz);
    vec3 n = vec3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0);
    // Typical paths inside a cloud: along the view through the layer, the light through its height.
    float viewPath = min(through, MAX_PATH);
    float lightPath = min(CLOUD_HEIGHT * 0.5 / max(abs(l.y), 0.05), MAX_PATH);
    float fadeIn = smoothstep(0.75 * r.edge, r.edge, r.start * r.horizontal);
    return vec4(farCloudShade(dir, n, t, viewPath, lightPath, r.far), CLOUD_OPACITY * hit * fadeIn);
}

vec4 farClouds(vec3 dir, float maxDistance, float footprint) {
    if (FarClouds.w < 0.5 || AtmosphereZenith.w < 0.5) {
        return vec4(0.0);
    }
    float bottom = CloudShadowField.z - CLOUD_HEIGHT * 0.5; // the layer's bottom, relative to the camera
    float top = bottom + CLOUD_HEIGHT;
    FarCloudRay r;
    r.horizontal = length(dir.xz);
    if (r.horizontal < 1e-4) {
        return vec4(0.0); // straight up or down: Java's mesh covers it
    }
    float enter;
    if (abs(dir.y) < 1e-5) {
        if (bottom > 0.0 || top < 0.0) {
            return vec4(0.0);
        }
        enter = 0.0;
        r.leave = 1e9;
    } else {
        float a = bottom / dir.y;
        float b = top / dir.y;
        enter = max(min(a, b), 0.0);
        r.leave = max(a, b);
    }
    r.edge = cloudMeshEdge();
    r.far = max(FarClouds.y, 1.0);
    // Past 1.9 × the cloud distance Bedrock's fog has made them all sky.
    r.start = max(enter, 0.75 * r.edge / r.horizontal);
    r.end = min(min(r.leave, maxDistance), 1.9 * r.far / r.horizontal);
    if (r.start >= r.end) {
        return vec4(0.0);
    }
    r.origin = vec3(CloudShadowField.x, -bottom, CloudShadowField.y);
    // Cells one pixel spans where the ray reaches the layer, along its slant: seen edge-on, a pixel stretches
    // across the layer by 1 / sin(elevation).
    float cellsPerPixel = r.start * footprint / (CLOUD_CELL * max(abs(dir.y), 0.02));
    float blend = smoothstep(0.35, 0.7, cellsPerPixel);
    vec4 traced = blend < 1.0 ? farCloudsTraced(dir, r) : vec4(0.0);
    vec4 filtered = blend > 0.0 ? farCloudsFiltered(dir, r, cellsPerPixel) : vec4(0.0);
    float a = mix(traced.a, filtered.a, blend);
    vec3 rgb = mix(traced.rgb * traced.a, filtered.rgb * filtered.a, blend) / max(a, 1e-5);
    return vec4(rgb, a);
}

// The view direction through a screen position, from two points along its ray, not from the camera to the
// near plane: view bobbing moves the camera by about as much as the near plane's distance, which tipped every
// sky direction up and down while walking (and the horizon's colours with it).
vec3 viewRay(vec2 uv) {
    vec4 near = InverseViewProjection * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec4 far = InverseViewProjection * vec4(uv * 2.0 - 1.0, 0.001, 1.0);
    return normalize(far.xyz / far.w - near.xyz / near.w);
}

// Distance to Distant Horizons' terrain at a screen position, from its own depth and projection; negative
// where it drew none.
float distantDistanceIn(sampler2D map, vec2 uv) {
    float d = texture(map, uv).r;
    bool cleared = DistantParams.y > 0.5 ? d <= 0.0 : d >= 1.0;
    if (cleared) {
        return -1.0;
    }
    float z = DistantParams.z > 0.5 ? d : d * 2.0 - 1.0;
    vec4 view = DistantInverseProjection * vec4(uv * 2.0 - 1.0, z, 1.0);
    return length(view.xyz / view.w);
}

float distantDistance(vec2 uv) {
    return distantDistanceIn(DistantDepth, uv);
}

// Distant Horizons' water, which DH draws as flat translucent colour: shaded here as the water pass shades
// Java's (water/terrain.fsh), with the ground under it lit like other terrain. A flat surface (DH has no
// per-texel normals out there) of water_still_grey's mean roughness 0.085 and the grey texel's F0, the
// water's extinction along the path to the ground, its sky reflection through Bedrock's split-sum term, and
// the sun's glint.
vec3 distantWaterSurface(vec3 below, vec3 ray, float surface, float ground, vec3 sunDir, vec3 moonDir) {
    vec3 n = vec3(0.0, 1.0, 0.0);
    vec3 v = -ray;
    float nDotV = dot(n, v);
    if (nDotV <= 0.0) {
        return below; // seen from below its level: a side face, left as the ground behind it
    }
    const float ROUGHNESS = 0.085;
    float fresnel = 0.02 + 0.98 * pow(1.0 - nDotV, 5.0);
    vec3 transmittance = exp(-UnderwaterMedium.rgb * max(ground - surface, 0.0));
    // F0 = mix(0.04, grey 0.371 (linear), metalness 0.0667), as for the near water.
    float f0 = mix(0.04, 0.371, 0.0667);
    float specularScale;
    if (DebugParams.z > 0.5) {
        vec2 ab = texture(BrdfLut, vec2(nDotV, 1.0 - ROUGHNESS)).rg;
        specularScale = f0 * ab.x + ab.y;
    } else {
        specularScale = (f0 + (1.0 - ROUGHNESS - f0) * pow(1.0 - nDotV, 5.0)) * (1.0 - 0.7 * ROUGHNESS);
    }
    vec3 mirror = reflect(ray, n);
    // DH's terrain has full sky light: the probe at IBLParameters.z 0.5.
    vec3 sky = AtmosphereZenith.w > 0.5 ? bedrockSkyReflection(mirror, ROUGHNESS, sunDir, moonDir)
            : mix(SkyColor.rgb, SkyHorizonColor.rgb, 0.3);
    vec3 glint = vec3(0.0);
    float nDotL = dot(n, sunDir);
    if (nDotL > 0.0) {
        vec3 h = normalize(sunDir + v);
        float fh = 0.02 + 0.98 * pow(1.0 - max(dot(h, v), 0.0), 5.0);
        float geometry = smithG1(nDotV, ROUGHNESS) * smithG1(nDotL, ROUGHNESS);
        glint = SunColor.rgb * fh * ggx(max(dot(n, h), 0.0), ROUGHNESS) * geometry / (4.0 * nDotV);
    }
    float path = max(ground - surface, 0.0);
    return specularScale * sky * 0.5 + glint
            + (1.0 - fresnel) * (below * transmittance + waterInScatter(ray, min(path, 64.0)));
}

// Distant Horizons' terrain carries only colour and depth: the normal is rebuilt from the depth of the
// neighbouring pixels (the nearer one on each side, so edges don't bend it), facing the camera.
vec3 distantNormal(vec2 uv, vec3 position) {
    vec2 texel = 1.0 / vec2(textureSize(DistantDepth, 0));
    vec3 neighbours[4];
    vec2 offsets[4] = vec2[](vec2(texel.x, 0.0), vec2(-texel.x, 0.0), vec2(0.0, texel.y), vec2(0.0, -texel.y));
    for (int i = 0; i < 4; i++) {
        float d = distantDistance(uv + offsets[i]);
        neighbours[i] = d < 0.0 ? position : viewRay(uv + offsets[i]) * d;
    }
    vec3 dx = length(neighbours[0] - position) < length(neighbours[1] - position)
            ? neighbours[0] - position : position - neighbours[1];
    vec3 dy = length(neighbours[2] - position) < length(neighbours[3] - position)
            ? neighbours[2] - position : position - neighbours[3];
    vec3 n = normalize(cross(dx, dy));
    if (any(isnan(n)) || dot(n, n) < 0.5) {
        n = vec3(0.0, 1.0, 0.0);
    }
    return dot(n, position) > 0.0 ? -n : n;
}

void main() {
    float depth = texture(Depth, texCoord).r;
    vec3 ray = viewRay(texCoord);
    // The angle one pixel spans, for the far clouds (taken here, where every pixel runs it).
    float footprint = 0.5 * (length(dFdx(ray)) + length(dFdy(ray)));
    // Distant Horizons' terrain, where vanilla terrain doesn't cover the pixel: lit here like the rest, so it
    // matches the near terrain instead of carrying its own lighting and fog.
    float distant = depth <= 0.0 && DistantParams.x > 0.5 ? distantDistance(texCoord) : -1.0;
    // DH's water: where its final depth is nearer than the ground under it.
    float distantWater = depth <= 0.0 && DistantParams.x > 0.5 && DistantParams.w > 0.5
            ? distantDistanceIn(DistantWaterDepth, texCoord) : -1.0;
    bool distantWaterHere = distantWater >= 0.0 && (distant < 0.0 || distantWater < distant - 0.25);
    if (distantWaterHere && distant < 0.0) {
        distant = distantWater + 64.0; // nothing drawn under it: deep water
    }
    if (depth <= 0.0 && distant < 0.0) {
        // No terrain (reversed depth: 0 is the cleared far plane): sky.
        if (AtmosphereZenith.w > 0.5) {
            // Bedrock's sky, exposed like everything else. Java's sky gradient was skipped and its sun, moon
            // and stars drawn over black (SkyRendererMixin), so the sky adds onto them: alpha 0 under the
            // premultiplied blend.
            vec3 sky = bedrockSkyRadiance(ray, normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
                    * AtmosphereHorizon.w;
            // Clouds beyond Java's cloud mesh, in front of the sky (and of Java's sun, moon and stars).
            vec4 clouds = farClouds(ray, 1e9, footprint);
            sky = mix(sky, clouds.rgb, clouds.a);
            // Volumetric fog in front of the sky, out to its far plane (under water, through the water above).
            vec3 fogTransmittance;
            vec3 fogLight = volumetricFog(ray, VOLUME_FAR, fogTransmittance);
            sky = sky * fogTransmittance + fogLight;
            meterLuminance = vec4(-dot(sky, LUMA), 0.0, 0.0, 1.0);
            hdrColor = vec4(sky, 1.0);
            // Premultiplied: where far clouds cover the sky they hide Java's sun, moon and stars behind it.
            fragColor = vec4(toDisplay(sky), clouds.a);
            return;
        }
        // Java's sky stays (alpha 0 adds nothing), but it's metered, as Bedrock's histogram counts the sky
        // too: its colours, horizon to zenith by the view's elevation, scaled to radiance.
        vec3 skyRadiance = DebugParams.y * mix(SkyHorizonColor.rgb, SkyColor.rgb, clamp(ray.y, 0.0, 1.0));
        // Negative marks sky for the meter's diagnostics; the histogram uses the magnitude (AutoExposure).
        meterLuminance = vec4(-dot(skyRadiance, LUMA), 0.0, 0.0, 1.0);
        hdrColor = vec4(0.0);
        fragColor = vec4(0.0);
        return;
    }

    vec3 albedo;
    vec3 n;
    vec4 mers;
    vec2 levels;
    vec3 position;
    if (distant >= 0.0) {
        // Distant Horizons' colour is its own lit terrain colour; it stands in for the albedo (level 1: no
        // normals or materials from it), with a rough dielectric material and full sky light.
        position = ray * distant;
        // DH multiplied its colour by Minecraft's lightmap (darker at night); divided back out here.
        vec3 lodColor = texture(DistantColor, texCoord).rgb / max(DistantLightmap.rgb, vec3(0.05));
        albedo = toLinear(clamp(lodColor, 0.0, 1.0));
        n = distantNormal(texCoord, position);
        mers = vec4(0.0, 0.0, 1.0, 0.0);
        levels = vec2(8.0, 248.0) / 256.0;
    } else {
        albedo = toLinear(texture(Albedo, texCoord).rgb);
        n = normalize(texture(Normal, texCoord).rgb * 2.0 - 1.0);
        mers = texture(Material, texCoord);
        levels = texture(Light, texCoord).rg;
        vec4 world = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
        position = world.xyz / world.w;
    }

    float metalness = mers.r;
    float emissive = mers.g;
    float roughness = clamp(mers.b, 0.05, 1.0);

    vec3 v = normalize(-position); // the camera is at the origin

    // Minecraft's light coordinates are (level * 16 + 8) / 256; map back to 0..1.
    vec2 light = clamp((levels * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float blockLight = light.x;
    float skyLight = light.y;

    vec3 diffuseColor = albedo * (1.0 - metalness);
    vec3 f0 = mix(vec3(0.04), albedo, metalness);
    // Sky light alone: the stand-in beyond the shadow map, and a guard inside it, since Sodium only hands the
    // shadow pass the chunks the camera sees, so a cave's roof may be missing from the map.
    float skyFallback = pow(skyLight, 4.0);
    // Bedrock's sky light heuristic: no sun or moon at all where there's no sky light.
    float skyGuard = skyLight > 0.0 ? 1.0 : 0.0;

    vec3 sunDir = normalize(SunDirection.xyz);
    vec3 moonDir = normalize(MoonDirection.xyz);
    bool moonCasts = ShadowParams.w > 0.5;
    float edgeFade;
    float mapped = shadowVisibility(snapToTexel(position, n), n, moonCasts ? moonDir : sunDir, edgeFade);
    float casterVisibility = mapped < 0.0 ? skyFallback : mix(skyFallback, mapped * skyGuard, edgeFade);
    if (DebugParams.x > 2.5) {
        // The water view: what the water column map says about this pixel's surface. Blue: the column has
        // mapped water but the point isn't under it (depth beyond the colour shows how far above); cyan to
        // dark green: under the water, by depth (0 to 8 blocks); grey: no water mapped in the column; dark red:
        // outside the map's range.
        vec3 tp = waterTestPoint(position);
        vec3 shown = vec3(0.35);
        if (WaterColumnOrigin.w < 0.5 || max(abs(tp.x), abs(tp.z)) > WATER_COLUMN_RANGE) {
            shown = vec3(0.35, 0.0, 0.0);
        } else {
            ivec2 texel = ivec2(mod(floor(tp.xz + WaterColumnOrigin.xz), 256.0));
            vec4 column = texelFetch(WaterColumnMap, texel, 0) * 255.0;
            float surface = (column.r + column.g * 256.0) / 16.0;
            float underBy = depthBelowWaterSurface(tp);
            if (underBy >= 0.0) {
                shown = mix(vec3(0.0, 1.0, 1.0), vec3(0.0, 0.3, 0.0), clamp(underBy / 8.0, 0.0, 1.0));
            } else if (surface > 0.0) {
                shown = vec3(0.1, 0.2, 1.0);
            }
        }
        fragColor = vec4(shown, 1.0);
        meterLuminance = vec4(0.18, 0.0, 0.0, 1.0);
        hdrColor = vec4(0.0);
        return;
    }
    if (DebugParams.x > 0.5) {
        vec3 shown = mapped < 0.0 ? vec3(1.0, 0.0, 0.0) : vec3(mapped);
        if (DebugParams.x > 1.5) {
            // The cascade view: the shadow test tinted by the nearest cascade (white, yellow, cyan, magenta).
            int c = nearestCascade(snapToTexel(position, n));
            vec3 tint = c == 0 ? vec3(1.0) : c == 1 ? vec3(1.0, 1.0, 0.3) : c == 2 ? vec3(0.3, 1.0, 1.0)
                    : c == 3 ? vec3(1.0, 0.3, 1.0) : vec3(0.3);
            shown = (mapped < 0.0 ? 0.5 : 0.35 + 0.65 * mapped) * tint;
        }
        fragColor = vec4(shown, 1.0);
        meterLuminance = vec4(0.18, 0.0, 0.0, 1.0);
        hdrColor = vec4(0.0);
        return;
    }
    float sunVisibility = moonCasts ? skyFallback : casterVisibility;
    float moonVisibility = moonCasts ? casterVisibility : skyFallback;
    vec3 snapped = snapToTexel(position, n);
    sunVisibility = min(sunVisibility, cloudShadow(snapped, sunDir));
    moonVisibility = min(moonVisibility, cloudShadow(snapped, moonDir));

    vec3 color = directLight(n, v, sunDir, SunColor.rgb * sunVisibility, diffuseColor, f0, roughness);
    color += directLight(n, v, moonDir, MoonColor.rgb * moonVisibility, diffuseColor, f0, roughness);
    color *= causticsMultiplier(position, n);

    // Bedrock's indirect light (DiscreteIndirectCombinedLightingPass), one colour whatever the normal:
    // max(block light + sky ambient × s × mix(s⁴, s², camera sky light), ambient floor), less where emissive.
    // LightingState holds Bedrock's captured terms.
    float cameraSky = sqrt(VolumeCamera.x) * 15.0 / 16.0;
    float skyCurve = skyLight * mix(pow(skyLight, 4.0), skyLight * skyLight, cameraSky);
    vec3 indirect = max(BlockLightColor.rgb * blockLightColor(blockLight) + SkyColor.rgb * skyCurve, AmbientColor.rgb);
    color += diffuseColor * indirect * (1.0 - emissive);

    // Reflected sky: smooth and metallic surfaces mirror their surroundings. Until there are reflections, the
    // sky in the mirror direction stands in (Bedrock's own sky, else a gradient), fading out with roughness and
    // sky access.
    float nDotV = max(dot(n, v), 1e-4);
    vec3 mirror = reflect(-v, n);
    // The horizon color is the glow towards the sun at sunset; most of the horizon is closer to the zenith's
    // blue, so it gets at most 30% (at full weight, walls facing away from the sun turned pink).
    float horizonShare = 0.3 * (1.0 - clamp(mirror.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 skyInMirror = AtmosphereZenith.w > 0.5 ? bedrockSkyReflection(mirror, mers.b, sunDir, moonDir)
            : mix(SkyColor.rgb, SkyHorizonColor.rgb, horizonShare);
    // Bedrock's split-sum term (DeferredIndirectSpecular): F0 × A + B, with (A, B) from its BRDF table at
    // (saturate(N·V), 1 - roughness). Without the install, Schlick's approximation with roughness.
    vec3 specularScale;
    if (DebugParams.z > 0.5) {
        vec2 ab = texture(BrdfLut, vec2(clamp(dot(n, v), 0.0, 1.0), 1.0 - mers.b)).rg;
        specularScale = f0 * ab.x + ab.y;
    } else {
        vec3 grazingF0 = max(vec3(1.0 - roughness), f0);
        specularScale = (f0 + (grazingF0 - f0) * pow(1.0 - nDotV, 5.0)) * (1.0 - 0.7 * roughness);
    }
    // Bedrock's sky reflection: faded in over sky light 12 to 15 (cubed), at IBLParameters.z 0.5.
    float iblFade = pow(clamp((skyLight * 15.0 - 12.0) / 3.0, 0.0, 1.0), 3.0) * 0.5;
    vec3 reflected = skyInMirror * iblFade;
    // Metals by torches (DeferredIndirectSpecular): where the reflected sky is dimmer, they reflect the block
    // light instead, BlockLightIndirectSpecularIntensity (0.1 in captures) of the block light's ambient term,
    // blended up from the ambient floor by its luminance (in Bedrock's units) and times the metalness.
    vec3 blockSpecular = BlockLightColor.rgb * blockLightColor(blockLight) * 0.1;
    float blockShare = clamp(dot(blockSpecular, LUMA) * 100.0, 0.0, 1.0); // AutoExposure.UNIT_SCALE
    blockSpecular = mix(AmbientColor.rgb, blockSpecular, blockShare) * metalness;
    if (dot(reflected, LUMA) < dot(blockSpecular, LUMA)) {
        reflected = blockSpecular;
    }
    float ssrWeight = 1.0 - smoothstep(0.3, 0.4, roughness);
    if (ssrWeight > 0.0) {
        vec4 traced = terrainReflection(position + n * 0.02, mirror);
        reflected = mix(reflected, traced.rgb, traced.a * ssrWeight);
    }
    color += reflected * specularScale;
    color += albedo * emissive * 2.0;
    if (distantWaterHere) {
        color = distantWaterSurface(color, ray, distantWater, distant, sunDir, moonDir);
        position = ray * distantWater;
    }

    // Distance fog: like Bedrock, the terrain fades into the sky behind it towards the render distance's edge.
    float fog = AtmosphereZenith.w > 0.5 ? bedrockFogAmount(length(position)) : 0.0;
    if (fog > 0.0) {
        // SurfaceRadianceUpscalePass fogs towards mix(UndergroundFogColor, sky, max(camera sky light, the
        // pixel's sky light)): enclosed spaces fog into the underground colour. Captured UndergroundFogColor:
        // 0 in air, (0, 0.016, 0.016) under water, scaled like the sky radiance (TUNE: its units weren't traced;
        // either way it is close to black).
        vec3 underground = UnderwaterParams.x > 0.5 ? vec3(0.0, 0.016, 0.016) * AtmosphereHorizon.w : vec3(0.0);
        float open = max(cameraSky, skyLight);
        vec3 fogColor = mix(underground, bedrockSkyRadiance(normalize(position), sunDir, moonDir) * AtmosphereHorizon.w, open);
        color = mix(color, fogColor, fog);
    }
    // Far clouds in front of the terrain (Distant Horizons' reaches past Java's cloud mesh).
    vec4 clouds = farClouds(ray, length(position), footprint);
    color = mix(color, clouds.rgb, clouds.a);

    vec3 viewDir = normalize(position);
    vec3 fogTransmittance;
    vec3 fogLight = volumetricFog(viewDir, length(position), fogTransmittance);
    // Under water, the march's water medium is Bedrock's WaterExtinction along the view, up to the surface.
    // From above water, what lies under the surface (within the water column map) is dimmed by the water
    // above it here; the water pass then leaves it alone.
    if (UnderwaterParams.x < 0.5) {
        // Nudged off the surface along its normal: the floor's top face lies exactly on the water body's
        // bottom (as for the caustics test).
        color *= waterTransmittanceTo(position, viewDir);
    }
    color = color * fogTransmittance + fogLight;

    meterLuminance = vec4(dot(color, LUMA), 0.0, 0.0, 1.0);
    hdrColor = vec4(color, 1.0);
    // The local exposure debug view: mid-grey, which the local exposure pass multiplies by its factor.
    fragColor = abs(DebugParams.w - 1.0) < 0.5 ? vec4(vec3(0.5), 1.0) : vec4(toDisplay(color), 1.0);
}
