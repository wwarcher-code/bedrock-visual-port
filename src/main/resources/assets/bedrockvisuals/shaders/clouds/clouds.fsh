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

uniform sampler2D BrdfLut;    // Bedrock's renderer/brdf_lut.png (BrdfLut.java)
uniform sampler2D CloudCells; // which cells of the field are filled (CloudShadows.java)
#define CLOUD_CELLS CloudCells
#define CLOUD_BRDF BrdfLut
#include <bedrockvisuals:cloud_lighting.glsl>

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
    vec3 color = cloudLight(view, n, viewPath, lightPath);

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
    // Java's cloud range as CloudRenderer.prepare gets it (FarClouds.x), so the lighting pass's far clouds fade
    // in exactly where these fade out; FogCloudsEnd before it's known.
    float range = FarClouds.x > 0.0 ? FarClouds.x : FogCloudsEnd;
    float cells = ceil(max(range, 1.0) / CellSize.x);
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
