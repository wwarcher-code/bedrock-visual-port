#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's cloud lighting (CloudsForwardPBR, see docs/bedrock-renderer-notes.md, "Clouds"), on Java's cloud
// mesh. Each cloud cell is lit by:
// - the sky's ambient light, times the cloud colour;
// - the sun (or moon) scattered through the cloud: a Henyey-Greenstein phase, faded by how thick the cloud is
//   along the view and by the light's path through it (Beer-Lambert);
// - wrapped diffuse from the sun on the face, plus light through the back of thin parts;
// - a little reflected sky;
// then blended towards the sky behind it with distance, and exposed and tone mapped like everything else.
// Like Bedrock, those paths run on through neighbouring filled cells.

#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>
#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>
#define LOCAL_EXPOSURE_APPLY
#include <bedrockvisuals:local_exposure.glsl>

layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};

layout(location = 0) in vec3 cloudPosition;
layout(location = 1) in vec3 cellPosition;
layout(location = 2) flat in vec3 cloudNormal;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Bedrock's CloudLightingUniforms and diffuse wrap, from GPU captures (the same at every time of day):
// x: thickness (blocks) over which the scattered and back light fade, y: Henyey-Greenstein g, z: reflected sky,
// w: extinction per block along the light's path.
const vec4 CLOUD_LIGHTING = vec4(16.0, 0.5, 5.0, 0.0625);
const float DIFFUSE_WRAP = 0.307;
// CloudColor.a in the captures.
const float CLOUD_OPACITY = 0.9;
// Bedrock caps the paths at 16 blocks.
const float MAX_PATH = 16.0;

uniform sampler2D BrdfLut;    // Bedrock's renderer/brdf_lut.png (BrdfLut.java)
uniform sampler2D CloudCells; // which cells of the field are filled (CloudShadows.java)

bool cellFilled(ivec2 cell) {
    ivec2 size = ivec2(CloudShadowSize.xy);
    return texelFetch(CloudCells, ((cell % size) + size) % size, 0).r > 0.5;
}

// Distance from p along d until the ray leaves the cloud, capped at MAX_PATH: out of the layer's top or
// bottom, or into an empty cell. p is in the cloud field (x, z in blocks; y from the layer's bottom). Walks
// the cell grid, so a path runs on through neighbouring filled cells as Bedrock's does; per-cell paths would
// light every cell on its own and show the grid.
float cloudPath(vec3 p, vec3 d) {
    float height = CellSize.y;
    float tMax = MAX_PATH;
    if (d.y > 1e-5) {
        tMax = min(tMax, (height - p.y) / d.y);
    } else if (d.y < -1e-5) {
        tMax = min(tMax, -p.y / d.y);
    }
    tMax = max(tMax, 0.0);
    float cellSize = CellSize.x;
    ivec2 cell = ivec2(floor(p.xz / cellSize));
    vec2 dir = d.xz;
    ivec2 stepDir = ivec2(sign(dir));
    vec2 safeDir = vec2(abs(dir.x) > 1e-6 ? dir.x : 1e-6, abs(dir.y) > 1e-6 ? dir.y : 1e-6);
    vec2 tDelta = abs(vec2(cellSize) / safeDir);
    vec2 nextEdge = (vec2(cell) + step(0.0, dir)) * cellSize;
    vec2 tNext = (nextEdge - p.xz) / safeDir;
    tNext = mix(vec2(1e9), tNext, greaterThan(abs(dir), vec2(1e-6)));
    float t = 0.0;
    // 16 blocks cross at most 3 cell edges.
    for (int i = 0; i < 4; i++) {
        if (!cellFilled(cell)) {
            return t;
        }
        float edge = min(tNext.x, tNext.y);
        if (edge >= tMax) {
            return tMax;
        }
        t = edge;
        if (tNext.x < tNext.y) {
            tNext.x += tDelta.x;
            cell.x += stepDir.x;
        } else {
            tNext.y += tDelta.y;
            cell.y += stepDir.y;
        }
    }
    return min(t, tMax);
}

// The fallback before Java's clouds have loaded: distance from p (inside the cell) to where the ray along d
// leaves it.
float exitDistance(vec3 p, vec3 d) {
    vec3 size = max(CellSize, vec3(1e-3));
    vec3 far = mix(-p, size - p, step(0.0, d));
    vec3 t = vec3(abs(d.x) > 1e-5 ? far.x / d.x : MAX_PATH,
             abs(d.y) > 1e-5 ? far.y / d.y : MAX_PATH,
             abs(d.z) > 1e-5 ? far.z / d.z : MAX_PATH);
    return clamp(min(t.x, min(t.y, t.z)), 0.0, MAX_PATH);
}

float cloudAlpha();
float edgeFade();

vec4 cloudColor() {
    vec3 view = normalize(cloudPosition);
    vec3 n = cloudNormal;
    bool moonLights = ShadowParams.w > 0.5;
    vec3 l = normalize(moonLights ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moonLights ? MoonColor.rgb : SunColor.rgb;
    // Bedrock's cloud colour: white by day, (0.1, 0.1, 0.15) at midnight in the captures, greyed by rain and
    // thunder (Weather.java). Java's own CloudColor isn't used.
    vec3 albedo = VolumeCamera.yzw;

    // How far the view travels inside the cloud from this face, and the light's path from the middle of that.
    float viewPath;
    float lightPath;
    if (CloudShadowSize.w > 0.5) {
        // This point in the cloud field (as CloudShadows maps it), nudged along the view into its own cell.
        vec3 p = vec3(CloudShadowField.x + cloudPosition.x, clamp(cellPosition.y, 0.0, CellSize.y),
                CloudShadowField.y + cloudPosition.z) + view * 0.01;
        viewPath = cloudPath(p, view);
        lightPath = cloudPath(p + view * viewPath * 0.5, l);
    } else {
        viewPath = exitDistance(clamp(cellPosition, vec3(0.0), CellSize), view);
        vec3 middle = clamp(cellPosition + view * viewPath * 0.5, vec3(0.0), CellSize);
        lightPath = exitDistance(middle, l);
    }

    // Sky ambient (Bedrock's SkyAmbientLightColorIntensity is this renderer's sky fill).
    vec3 color = SkyColor.rgb * albedo;

    // Scattered sunlight. Bedrock's phase takes the light direction against the direction to the camera.
    float g = CLOUD_LIGHTING.y;
    float cosine = dot(l, -view);
    float phase = (1.0 - g * g) * 0.07957747 / pow(1.0 + g * (2.0 * cosine + g), 1.5);
    float thin = 1.0 - smoothstep(0.0, 1.0, clamp(viewPath * 0.5 / CLOUD_LIGHTING.x, 0.0, 1.0));
    float transmitted = exp(-lightPath * CLOUD_LIGHTING.w);
    color += phase * thin * transmitted * light;

    // Wrapped diffuse, and light through the back where the cloud is thin.
    float wrap = DIFFUSE_WRAP;
    float front = max((dot(n, l) + wrap) / ((1.0 + wrap) * (1.0 + wrap)), 0.0);
    float back = max((dot(-n, l) + wrap) / ((1.0 + wrap) * (1.0 + wrap)), 0.0)
            * (1.0 - smoothstep(0.0, 1.0, clamp(viewPath / CLOUD_LIGHTING.x, 0.0, 1.0)));
    color += (front + back) / PI * albedo * light;

    // Reflected sky: Bedrock's probe at its roughest mip times its BRDF table at roughness 1 and F0 0.04.
    if (AtmosphereZenith.w > 0.5) {
        float nDotV = clamp(dot(n, -view), 0.0, 1.0);
        float fresnel;
        if (DebugParams.z > 0.5) {
            vec2 ab = texture(BrdfLut, vec2(nDotV, 0.0)).rg;
            fresnel = 0.04 * ab.x + ab.y;
        } else {
            // The table's roughness-1 row, roughly.
            fresnel = 0.04 * mix(0.57, 0.31, nDotV) + 0.016 * (1.0 - nDotV);
        }
        color += CLOUD_LIGHTING.z * fresnel * bedrockSkyReflection(reflect(view, n), 1.0, normalize(SunDirection.xyz),
                normalize(MoonDirection.xyz));
    }

    // Towards the sky behind with distance. Bedrock: saturate(distance / DistanceControl.x - 0.9), where
    // DistanceControl.x is its cloud range (3 × the render distance; 1152 in the captures), far past where its
    // cloud field ends. Java's mesh ends much closer (meshEdge), so the same blend runs over the last quarter of
    // Java's mesh instead, finishing before cells can pop in or out at its edge.
    if (AtmosphereZenith.w > 0.5) {
        color = mix(color, bedrockSkyRadiance(view, normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
                * AtmosphereHorizon.w, edgeFade());
    }
    // Local exposure, as Bedrock applies it to the whole HDR scene (the clouds are drawn after that pass).
    return vec4(toDisplay(locallyExposed(color)), cloudAlpha());
}

// Java builds its cloud mesh from the cells within ceil(range / 12) cells of the camera's cell (a circle,
// CloudRenderer.buildMesh), where range is FogCloudsEnd in blocks. Every point closer than about 1.5 cells
// inside that circle is always covered, wherever the camera sits in its cell; past it, cells appear and vanish
// as the clouds drift, which flickers unless they're already faded out.
float meshEdge() {
    float cells = ceil(max(FogCloudsEnd, 1.0) / CellSize.x);
    return max((cells - 1.5) * CellSize.x, CellSize.x);
}

// 0 near the camera, 1 at the mesh edge (horizontal distance).
float edgeFade() {
    float edge = meshEdge();
    return smoothstep(0.75 * edge, edge, length(cloudPosition.xz));
}

// Bedrock's clouds keep their opacity and fade into the sky instead; here they also fade out where the sky
// blend finishes, so the edge of Java's mesh never shows.
float cloudAlpha() {
    return CLOUD_OPACITY * (1.0 - edgeFade());
}

void main() {
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, cloudAlpha());
    #else
    vec4 color = cloudColor();
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    fragColor = color;
    #endif
}
