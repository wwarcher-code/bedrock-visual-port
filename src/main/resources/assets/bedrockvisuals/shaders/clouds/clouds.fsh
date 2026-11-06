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
#define SKY_PROBE SkyProbe
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
uniform sampler2D DistantDepth; // Distant Horizons' depth this frame (LightingPass.java), when its terrain is lit
layout(std140) uniform DistantData {
    mat4 DistantInverseProjection; // Distant Horizons' clip space to its view space
    vec4 DistantParams;            // x: its terrain is lit here, y: reversed depth, z: [0, 1] clip depth
    vec4 DistantLightmap;
};

// Whether Distant Horizons' terrain lies in front of this fragment. Java's depth buffer doesn't hold DH's
// terrain (the lighting pass composites it from DH's own depth), so without this the cloud mesh would draw
// over distant mountains. The same distance the lighting pass uses (deferred.fsh, distantDistance).
bool behindDistantTerrain() {
    if (DistantParams.x < 0.5) {
        return false;
    }
    vec2 uv = gl_FragCoord.xy / max(ScreenParams.xy, vec2(1.0));
    float d = texture(DistantDepth, uv).r;
    bool cleared = DistantParams.y > 0.5 ? d <= 0.0 : d >= 1.0;
    if (cleared) {
        return false;
    }
    float z = DistantParams.z > 0.5 ? d : d * 2.0 - 1.0;
    vec4 view = DistantInverseProjection * vec4(uv * 2.0 - 1.0, z, 1.0);
    return length(view.xyz / view.w) < length(cloudPosition);
}
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
    // The handover to the lighting pass's far clouds (CloudShadows.handover: the mesh's edge, or nearer where
    // its cells would be under half a pixel tall), so those fade in exactly where these fade out; Java's
    // cloud range (FogCloudsEnd) before it's known.
    if (FarClouds.z > 0.0) {
        return FarClouds.z;
    }
    float range = FogCloudsEnd;
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
    // Every OIT stage drops the same fragments, so the stages stay consistent.
    if (behindDistantTerrain()) {
        discard;
    }
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
