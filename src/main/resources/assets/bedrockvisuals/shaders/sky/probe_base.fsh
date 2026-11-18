#version 330
#extension GL_ARB_separate_shader_objects : require

// The reflection probe's base (SkyProbe.java), in every direction of an octahedral map, in this renderer's
// units: the terrain around the camera as the scene probe last captured and lit it (SceneProbe.java), as
// Bedrock's probe holds its scene, and Bedrock's sky radiance (the function its SkyProbeDeferredShading lights
// the probe's sky with) where no terrain was drawn, behind the clouds: Bedrock draws its clouds
// (CloudsForwardPBR) into the probe before lighting it (GPU captures: three cloud draws ahead of each
// SkyProbeDeferredShading), so water reflects the clouds overhead. Here the whole cloud layer is traced
// (far_clouds.glsl), lit as Java's mesh and the far clouds are. Where the scene probe saw sky, Distant Horizons'
// terrain beyond Sodium's chunks is found on the far terrain's height map (DistantHeights.java) and lit as the
// scene probe's terrain is (probe/light.fsh), so far hills reflect in the water as Bedrock's probe holds all the
// terrain it draws.

uniform sampler2D ProbeFace0; // the lit faces, +x, -x, +y, -y, +z, -z: rgb light, alpha 1 where terrain
uniform sampler2D ProbeFace1;
uniform sampler2D ProbeFace2;
uniform sampler2D ProbeFace3;
uniform sampler2D ProbeFace4;
uniform sampler2D ProbeFace5;

uniform sampler2D CloudShadowMap; // filled cloud cells (CloudShadows.java)
uniform sampler2D CloudCoverage;  // their share per area, mipmapped
uniform sampler2D BrdfLut;        // Bedrock's BRDF table (the clouds' reflected sky)
uniform sampler2D DistantHeightMap;  // the far terrain's heights, a cell of 8 blocks (DistantHeights.java)
uniform sampler2D DistantColourMap;  // and its top blocks' colours (sRGB; alpha 1 where known)

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>
#define CLOUD_CELLS CloudShadowMap
#define CLOUD_BRDF BrdfLut
#include <bedrockvisuals:cloud_lighting.glsl>
#define CLOUD_COVERAGE CloudCoverage
#include <bedrockvisuals:far_clouds.glsl>

uniform sampler2D PreviousBase; // the base before the newest capture, faded out over a second (SkyProbe)

layout(std140) uniform ProbeBlend {
    vec4 ProbeBlendParams; // x: the new capture's share, y: 1 while fading
};

layout(std140) uniform ProbeDistant {
    vec4 DistantMapOrigin; // xz: the camera's world position modulo the map's span, y: its height above the
                           // level's floor, w: 1 when the map holds heights
    vec4 DistantMarch;     // x: where the march starts (blocks; nearer terrain is the scene probe's), y: where it
                           // ends, z: the top of the map's highest terrain above the camera
};

layout(std140) uniform ProbeFaces {
    mat4 FaceViewProjection[6]; // camera-relative world space to each face's clip space, at the capture
    vec4 ProbeFacesParams;      // x: 1 when faces have been captured; yzw: the camera's move since (blocks)
};

// The probe's size follows the quality preset's reflections (SkyProbe: 512 at ultra, 128 below).
#define PROBE_SIZE SkyProbeParams.w

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec4 faceTexel(int face, vec2 uv) {
    if (face == 0) return texture(ProbeFace0, uv);
    if (face == 1) return texture(ProbeFace1, uv);
    if (face == 2) return texture(ProbeFace2, uv);
    if (face == 3) return texture(ProbeFace3, uv);
    if (face == 4) return texture(ProbeFace4, uv);
    return texture(ProbeFace5, uv);
}

// The captured scene in direction d: rgb premultiplied by alpha (1 where terrain).
vec4 captured(vec3 d) {
    vec3 a = abs(d);
    int face = a.x >= a.y && a.x >= a.z ? (d.x > 0.0 ? 0 : 1) : a.y >= a.z ? (d.y > 0.0 ? 2 : 3) : (d.z > 0.0 ? 4 : 5);
    vec4 clip = FaceViewProjection[face] * vec4(d * 16.0, 1.0);
    if (clip.w <= 0.0) {
        return vec4(0.0);
    }
    vec2 uv = clamp(clip.xy / clip.w * 0.5 + 0.5, vec2(0.0), vec2(1.0));
    vec4 texel = faceTexel(face, uv);
    return vec4(texel.rgb, texel.a);
}

const float HEIGHT_CELL = 8.0;
const float HEIGHT_SIZE = 1024.0;
const int DISTANT_STEPS = 96;

// The far terrain's height at camera-relative xz, relative to the camera; -1e9 where the map has none.
float distantHeight(vec2 xz) {
    ivec2 texel = ivec2(mod(floor((xz + DistantMapOrigin.xz) / HEIGHT_CELL), HEIGHT_SIZE));
    float height = texelFetch(DistantHeightMap, texel, 0).r * 65535.0;
    return height > 0.0 ? height - DistantMapOrigin.y : -1e9;
}

// Distant Horizons' terrain in direction d (DistantHeights), lit; alpha 1 where the ray meets it.
vec4 distantTerrain(vec3 d) {
    if (DistantMapOrigin.w < 0.5 || DistantMarch.y <= DistantMarch.x) {
        return vec4(0.0);
    }
    float t = DistantMarch.x;
    // A ray already above the highest terrain at the start, and climbing, meets none.
    if (d.y > 0.0 && d.y * t > DistantMarch.z) {
        return vec4(0.0);
    }
    float previous = t;
    bool hit = false;
    for (int i = 0; i < DISTANT_STEPS; i++) {
        vec3 p = d * t;
        if ((d.y > 0.0 && p.y > DistantMarch.z) || p.y + DistantMapOrigin.y < 0.0 || t > DistantMarch.y) {
            return vec4(0.0); // above the highest terrain and climbing, or past the far terrain's reach
        }
        if (p.y < distantHeight(p.xz)) {
            hit = true;
            break;
        }
        previous = t;
        t += max(0.5 * HEIGHT_CELL, t * 0.035);
    }
    if (!hit) {
        return vec4(0.0);
    }
    // Where the ray crosses the cell's top, or meets its side.
    float a = previous;
    float b = t;
    for (int j = 0; j < 5; j++) {
        float m = 0.5 * (a + b);
        vec3 q = d * m;
        if (q.y < distantHeight(q.xz)) {
            b = m;
        } else {
            a = m;
        }
    }
    vec3 p = d * b;
    // The slope across the neighbouring cells; a cell missing beside it counts as level.
    float here = distantHeight(p.xz);
    float east = distantHeight(p.xz + vec2(HEIGHT_CELL, 0.0));
    float west = distantHeight(p.xz - vec2(HEIGHT_CELL, 0.0));
    float south = distantHeight(p.xz + vec2(0.0, HEIGHT_CELL));
    float north = distantHeight(p.xz - vec2(0.0, HEIGHT_CELL));
    east = east < -1e8 ? here : east;
    west = west < -1e8 ? here : west;
    south = south < -1e8 ? here : south;
    north = north < -1e8 ? here : north;
    vec3 n = normalize(vec3((west - east) / (2.0 * HEIGHT_CELL), 1.0, (north - south) / (2.0 * HEIGHT_CELL)));
    ivec2 texel = ivec2(mod(floor((p.xz + DistantMapOrigin.xz) / HEIGHT_CELL), HEIGHT_SIZE));
    vec4 colour = texelFetch(DistantColourMap, texel, 0);
    vec3 albedo = colour.a > 0.5 ? toLinear(colour.rgb) : vec3(0.18);
    // As probe/light.fsh lights the scene probe's terrain, open to the sky (sky light 15) and unshadowed.
    bool moonCasts = ShadowParams.w > 0.5;
    vec3 l = normalize(moonCasts ? MoonDirection.xyz : SunDirection.xyz);
    vec3 radiance = moonCasts ? moonLight() : sunLight();
    vec3 color = albedo / PI * radiance * max(dot(n, l), 0.0);
    color += albedo * max(SkyColor.rgb, AmbientColor.rgb);
    float fog = AtmosphereZenith.w > 0.5 ? bedrockFogAmount(b) : 0.0;
    if (fog > 0.0) {
        vec3 sky = bedrockSkyRadiance(d, normalize(SunDirection.xyz), normalize(MoonDirection.xyz)) * AtmosphereHorizon.w;
        color = mix(color, sky, fog);
    }
    return vec4(color, 1.0);
}

void main() {
    vec3 d = octahedralDecode((floor(gl_FragCoord.xy) + 0.5) / PROBE_SIZE);
    vec3 sky = bedrockSkyRadiance(d, normalize(SunDirection.xyz), normalize(MoonDirection.xyz)) * AtmosphereHorizon.w;
    // The clouds, from the camera out (no mesh in the probe), each texel's footprint about its angle.
    vec4 clouds = farCloudsBeyond(d, 1e9, 3.0 / PROBE_SIZE, 0.0);
    sky = mix(sky, clouds.rgb, clouds.a);
    vec4 scene = ProbeFacesParams.x > 0.5 ? captured(d) : vec4(0.0);
    if (scene.a < 0.5) {
        vec4 far = distantTerrain(d);
        scene = scene + far * (1.0 - scene.a);
    }
    fragColor = vec4(scene.rgb + sky * (1.0 - scene.a), 1.0);
    if (ProbeBlendParams.y > 0.5) {
        vec3 previous = texelFetch(PreviousBase, ivec2(gl_FragCoord.xy), 0).rgb;
        fragColor.rgb = mix(previous, fragColor.rgb, ProbeBlendParams.x);
    }
}
