#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's screen-space reflection march (SSRRayMarch), for the water seen from above (ReflectionPass.java):
// per pixel, where the view meets a water surface in front of the scene (the water column map, as the lighting
// pass finds it, or the sea in front of Distant Horizons' ground), the mirror ray off that surface's ripple
// normal is marched against Java's depth and DH's. Out: the hit's screen position (xy), the distance along the
// view to the reflecting point (z, for the resolve's reprojection), and the hit's confidence (w); w -1 for a
// miss (a gap, SSRFillGaps), -2 where no water is seen from above.
//
// The confidence is Bedrock's (SSRRayMarch, from its DXIL; SSRFadingParamsAndThickness (3, 3, 0.75, 0.025)):
// the least of the screen-edge fade (1 - |x|^(3 aspect)) (1 - |y|^3) at the hit, in normalised device
// coordinates; 1 - smoothstep(0.75, 1, f), f how far along its path on screen the hit lies, the path running
// from the start to where the ray leaves the screen or reaches the far plane (CameraData.y, 2496.6 blocks);
// and saturate(1 - N.ray) with the hit's normal, so back faces fade out.

uniform sampler2D Depth;
uniform sampler2D DistantDepth;
uniform sampler2D WaterColumnMap;
uniform sampler2D u_WaterNormal;
uniform sampler2D Normal; // the G-buffer's normals (world space, rgb * 2 - 1)

#include <bedrockvisuals:common.glsl>
#define WATER_COLUMNS WaterColumnMap
#include <bedrockvisuals:water_columns.glsl>

layout(std140) uniform DistantData {
    mat4 DistantInverseProjection;
    vec4 DistantParams;   // x: its terrain is lit here, y: reversed depth, z: [0, 1] clip depth
    vec4 DistantLightmap; // w: the sea's surface height relative to the camera
};

layout(std140) uniform WaterData {
    vec4 StillRect;
    vec4 FlowRect;
    vec4 Absorption;
    vec4 Scattering;
    vec4 Animation;   // x: the surface maps' frame, with its fraction, y: frames in them
    vec4 OverlayRect;
    vec4 WaveParams0; // Bedrock's waves (WaterPass.putWaves): enabled, frequency, octaves, depth
    vec4 WaveParams1; // speed, shape, pull, mix
    vec4 WaveParams2; // frequency scaling, speed scaling, time (s)
    vec4 WaveOrigin;  // xz: the camera from the waves' floating origin
};
#include <bedrockvisuals:water_waves.glsl>

// The quality preset's march (reflection_configuration.pc.json "ssr", ReflectionPass): x the linear search's
// steps (Bedrock's are a pixel each, ray_step_length 1: how far along its path on screen a ray is followed),
// y the binary search's, z fading_distance, w ssr_scale (the march target's share of the screen's resolution).
layout(std140) uniform MarchData {
    vec4 MarchParams;
};

// The linear search's reach in the screen's pixels (its steps are the march target's).
float stepBudget() {
    return MarchParams.x / max(MarchParams.w, 0.25);
}

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 marchOut;

const int STEPS = 64;
const int MAX_REFINE = 40;
const vec2 FADING = vec2(3.0, 3.0); // SSRFadingParamsAndThickness.xy
const float FAR_PLANE = 2496.6;            // CameraData.y

// The screen position of camera-relative point p.
vec2 screenOf(vec3 p, out float w) {
    vec4 clip = ViewProjection * vec4(p, 1.0);
    w = clip.w;
    return clip.xy / clip.w * 0.5 + 0.5;
}

// Bedrock's confidence in a hit at hitUv on the ray from start along dir (see above).
float hitConfidence(vec3 start, vec3 dir, vec2 hitUv, vec3 hitNormal, bool hasNormal) {
    vec2 size = vec2(textureSize(Depth, 0));
    vec2 ndc = abs(hitUv * 2.0 - 1.0);
    float edge = (1.0 - pow(ndc.x, FADING.x * size.x / size.y)) * (1.0 - pow(ndc.y, FADING.y));
    // The ray's path on screen: clipped at the far plane, and short of the camera's plane if it turns back.
    float w0;
    vec2 uv0 = screenOf(start, w0);
    float dw = (ViewProjection * vec4(dir, 0.0)).w;
    float reach = FAR_PLANE;
    if (dw < 0.0) {
        reach = min(reach, 0.99 * (w0 - 0.05) / -dw);
    }
    float w1;
    vec2 uv1 = screenOf(start + dir * reach, w1);
    vec2 path = uv1 - uv0;
    // Where the path leaves the screen, or the linear search runs out of steps, as a share of it.
    vec2 pathPixels = abs(path * size);
    float leave = min(1.0, stepBudget() / max(max(pathPixels.x, pathPixels.y), 1e-3));
    for (int axis = 0; axis < 2; axis++) {
        if (abs(path[axis]) > 1e-6) {
            float bound = path[axis] > 0.0 ? 1.0 : 0.0;
            leave = min(leave, max((bound - uv0[axis]) / path[axis], 0.0));
        }
    }
    float length2 = dot(path, path) * leave * leave;
    float along = length2 > 1e-12 ? dot(hitUv - uv0, path) * leave / length2 : 0.0;
    if (along > 1.0) {
        return 0.0; // past the linear search's last step
    }
    float travel = 1.0 - smoothstep(MarchParams.z, 1.0, clamp(along, 0.0, 1.0));
    float facing = hasNormal ? clamp(1.0 - dot(hitNormal, dir), 0.0, 1.0) : 1.0;
    return clamp(min(min(edge, travel), facing), 0.0, 1.0);
}

vec3 viewRay(vec2 uv) {
    vec4 near = InverseViewProjection * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec4 far = InverseViewProjection * vec4(uv * 2.0 - 1.0, 0.001, 1.0);
    return normalize(far.xyz / far.w - near.xyz / near.w);
}

float distantDistanceAt(vec2 uv) {
    if (DistantParams.x < 0.5) {
        return -1.0;
    }
    float d = texture(DistantDepth, uv).r;
    bool cleared = DistantParams.y > 0.5 ? d <= 0.0 : d >= 1.0;
    if (cleared) {
        return -1.0;
    }
    float z = DistantParams.z > 0.5 ? d : d * 2.0 - 1.0;
    vec4 view = DistantInverseProjection * vec4(uv * 2.0 - 1.0, z, 1.0);
    return length(view.xyz / view.w);
}

// The scene's distance from the camera at camera-relative point p's pixel; false off the screen (uv -1: the ray
// left it, and the scene probe, which holds the terrain around the camera, reflects what lies there) or where
// nothing was drawn. (Held to the screen's sides, a ray leaving sideways ran up the edge column: streaks.)
bool sceneAt(vec3 p, out float sceneDistance, out vec2 uv) {
    sceneDistance = -1.0;
    uv = vec2(-1.0);
    vec4 clip = ViewProjection * vec4(p, 1.0);
    if (clip.w <= 0.0) {
        return false;
    }
    uv = clip.xy / clip.w * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        uv = vec2(-1.0);
        return false;
    }
    float depth = texture(Depth, uv).r;
    if (depth > 0.0) {
        vec4 world = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth, 1.0);
        sceneDistance = length(world.xyz / world.w);
    } else {
        sceneDistance = distantDistanceAt(uv);
    }
    return sceneDistance >= 0.0;
}

// Bedrock's water_still_grey normal at world column position (the water's top face: the texture's u along x,
// v along z, 16 texels a block), blended between the animation's frames and read at the mip its footprint
// needs, as the water pass reads it.
vec3 rippleNormal(vec3 worldPosition, float texelsPerPixel) {
    ivec2 size = textureSize(u_WaterNormal, 0);
    int frameSize = max(size.x, 1);
    float frames = max(Animation.y, 1.0);
    float time = mod(Animation.x, frames);
    int f0 = int(floor(time));
    int f1 = int(mod(float(f0 + 1), frames));
    float level = clamp(log2(max(texelsPerPixel, 1.0)), 0.0, log2(float(frameSize)));
    int lod = int(floor(level + 0.5));
    ivec2 texel = ivec2(mod(floor(worldPosition.xz * 16.0), float(frameSize)));
    ivec2 t0 = ivec2(texel.x, texel.y + f0 * frameSize) >> lod;
    ivec2 t1 = ivec2(texel.x, texel.y + f1 * frameSize) >> lod;
    vec3 n0 = texelFetch(u_WaterNormal, t0, lod).xyz * 2.0 - 1.0;
    vec3 n1 = texelFetch(u_WaterNormal, t1, lod).xyz * 2.0 - 1.0;
    vec3 tangentNormal = normalize(mix(n0, n1, time - float(f0)));
    return normalize(vec3(tangentNormal.x, tangentNormal.z, tangentNormal.y));
}

void main() {
    marchOut = vec4(0.0, 0.0, 0.0, -2.0);
    if (UnderwaterParams.x > 0.5 || WaterColumnOrigin.w < 0.5) {
        return;
    }
    vec3 ray = viewRay(texCoord);
    if (ray.y > -1e-3) {
        return;
    }
    float depth = texture(Depth, texCoord).r;
    float sceneDistance = 1e9;
    vec3 scenePoint = ray * 1e3;
    if (depth > 0.0) {
        vec4 world = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
        scenePoint = world.xyz / world.w;
        sceneDistance = length(scenePoint);
    } else {
        float distant = distantDistanceAt(texCoord);
        if (distant > 0.0) {
            sceneDistance = distant;
            scenePoint = ray * distant;
        }
    }
    // The water surface the view meets: over the point seen, that column's surface followed back along the view
    // (the lighting pass's climb), else the sea's in front of DH's ground or the sky.
    float surfaceY = 1.0;
    vec3 probe = waterTestPoint(scenePoint);
    WaterColumn column;
    if (sceneDistance < 1e8 && depthBelowWaterSurface(probe) >= 0.0 && waterColumnAt(probe, column)) {
        surfaceY = column.surface - WaterColumnOrigin.y;
        for (int k = 0; k < 4; k++) {
            float above = depthBelowWaterSurface(waterTestPoint(ray * (surfaceY / ray.y)) + vec3(0.0, -0.05, 0.0));
            if (above <= 0.05) {
                break;
            }
            surfaceY += above;
        }
    } else if (DistantLightmap.w < 0.0) {
        float seaDistance = DistantLightmap.w / ray.y;
        WaterColumn sea;
        if (seaDistance < sceneDistance && waterColumnAt(ray * (seaDistance + 0.05), sea)) {
            surfaceY = DistantLightmap.w;
        }
    }
    if (surfaceY >= 0.0) {
        return;
    }
    float t = surfaceY / ray.y;
    if (t >= sceneDistance) {
        return;
    }
    vec3 origin = ray * t;
    // How many surface-map texels one pixel spans there: the pixel's angle, stretched by the grazing angle.
    float pixelAngle = length(viewRay(texCoord + vec2(0.0, 1.0 / float(textureSize(Depth, 0).y))) - ray);
    float texelsPerPixel = 16.0 * t * pixelAngle / max(-ray.y, 0.05);
    vec3 n = rippleNormal(origin + CameraGridOffset.xyz, texelsPerPixel);
    n = withWaves(n, vec3(0.0, 1.0, 0.0), origin);
    vec3 mirror = reflect(ray, n);
    // As the water pass: a ripple tilting the ray below the water's plane is kept just above it.
    if (mirror.y < 0.02) {
        mirror = normalize(vec3(mirror.x, 0.02 + 0.5 * abs(mirror.y), mirror.z));
    }
    // The march, in steps growing with the distance; at the first crossing behind the scene the crossing is
    // refined, and taken if it lies on the surface (else the ray passed behind something and goes on).
    float stepLength = 0.25;
    float travelled = 0.0;
    vec3 start = origin + vec3(0.0, 0.02, 0.0);
    vec3 previous = start;
    float startW;
    vec2 startUv = screenOf(start, startW);
    vec2 screenSize = vec2(textureSize(Depth, 0));
    for (int i = 0; i < STEPS; i++) {
        travelled += stepLength;
        stepLength *= 1.15;
        vec3 p = start + mirror * travelled;
        float here;
        vec2 uv;
        bool there = sceneAt(p, here, uv);
        if (uv.x < 0.0) {
            break; // off the screen: the scene probe's
        }
        vec2 travelled = abs((uv - startUv) * screenSize);
        if (max(travelled.x, travelled.y) > stepBudget()) {
            break; // past the linear search's last step: a miss, as Bedrock's
        }
        if (!there || length(p) <= here) {
            previous = p;
            continue;
        }
        vec3 front = previous;
        vec3 back = p;
        int refine = int(clamp(MarchParams.y, 1.0, float(MAX_REFINE)));
        for (int j = 0; j < MAX_REFINE; j++) {
            if (j >= refine) {
                break;
            }
            vec3 middle = (front + back) * 0.5;
            float middleScene;
            vec2 middleUv;
            if (sceneAt(middle, middleScene, middleUv) && length(middle) > middleScene) {
                back = middle;
            } else {
                front = middle;
            }
        }
        float backScene;
        vec2 hitUv;
        if (sceneAt(back, backScene, hitUv) && length(back) - backScene < max(length(back - front) * 4.0, 1.0)) {
            // Java's terrain has the G-buffer's normal there; Distant Horizons' (no Java depth) none.
            bool hasNormal = texture(Depth, hitUv).r > 0.0;
            vec3 hitNormal = hasNormal ? normalize(texture(Normal, hitUv).rgb * 2.0 - 1.0) : vec3(0.0);
            marchOut = vec4(hitUv, t, hitConfidence(start, mirror, hitUv, hitNormal, hasNormal));
            return;
        }
        previous = p;
    }
    // No terrain on the ray: the reflection probe (the sky, and the terrain the scene probe captured), as
    // Bedrock's SSR falls back to its probe. (Taking the sky's own pixel on screen instead left a hard step where
    // it met the probe beyond the screen's edges: the drawn sky carries the volumetric fog's haze, the probe's
    // sky doesn't.)
    marchOut = vec4(0.0, 0.0, t, -1.0);
}
