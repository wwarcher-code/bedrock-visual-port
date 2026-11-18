#version 330
#extension GL_ARB_separate_shader_objects : require

// The reflection probe's base (SkyProbe.java), in every direction of an octahedral map, in this renderer's
// units: the terrain around the camera as the scene probe last captured and lit it (SceneProbe.java), as
// Bedrock's probe holds its scene, and Bedrock's sky radiance (the function its SkyProbeDeferredShading lights
// the probe's sky with) where no terrain was drawn, behind the clouds: Bedrock draws its clouds
// (CloudsForwardPBR) into the probe before lighting it (GPU captures: three cloud draws ahead of each
// SkyProbeDeferredShading), so water reflects the clouds overhead. Here the whole cloud layer is traced
// (far_clouds.glsl), lit as Java's mesh and the far clouds are.

uniform sampler2D ProbeFace0; // the lit faces, +x, -x, +y, -y, +z, -z: rgb light, alpha 1 where terrain
uniform sampler2D ProbeFace1;
uniform sampler2D ProbeFace2;
uniform sampler2D ProbeFace3;
uniform sampler2D ProbeFace4;
uniform sampler2D ProbeFace5;

uniform sampler2D CloudShadowMap; // filled cloud cells (CloudShadows.java)
uniform sampler2D CloudCoverage;  // their share per area, mipmapped
uniform sampler2D BrdfLut;        // Bedrock's BRDF table (the clouds' reflected sky)

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

void main() {
    vec3 d = octahedralDecode((floor(gl_FragCoord.xy) + 0.5) / PROBE_SIZE);
    vec3 sky = bedrockSkyRadiance(d, normalize(SunDirection.xyz), normalize(MoonDirection.xyz)) * AtmosphereHorizon.w;
    // The clouds, from the camera out (no mesh in the probe), each texel's footprint about its angle.
    vec4 clouds = farCloudsBeyond(d, 1e9, 3.0 / PROBE_SIZE, 0.0);
    sky = mix(sky, clouds.rgb, clouds.a);
    vec4 scene = ProbeFacesParams.x > 0.5 ? captured(d) : vec4(0.0);
    fragColor = vec4(scene.rgb + sky * (1.0 - scene.a), 1.0);
    if (ProbeBlendParams.y > 0.5) {
        vec3 previous = texelFetch(PreviousBase, ivec2(gl_FragCoord.xy), 0).rgb;
        fragColor.rgb = mix(previous, fragColor.rgb, ProbeBlendParams.x);
    }
}
