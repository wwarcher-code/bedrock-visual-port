#version 330
#extension GL_ARB_separate_shader_objects : require

// The reflection probe's base (SkyProbe.java), in every direction of an octahedral map, in this renderer's
// units: the terrain around the camera as the scene probe last captured and lit it (SceneProbe.java), as
// Bedrock's probe holds its scene, and Bedrock's sky radiance (the function its SkyProbeDeferredShading lights
// the probe's sky with) where no terrain was drawn.

uniform sampler2D ProbeFace0; // the lit faces, +x, -x, +y, -y, +z, -z: rgb light, alpha 1 where terrain
uniform sampler2D ProbeFace1;
uniform sampler2D ProbeFace2;
uniform sampler2D ProbeFace3;
uniform sampler2D ProbeFace4;
uniform sampler2D ProbeFace5;

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>

layout(std140) uniform ProbeFaces {
    mat4 FaceViewProjection[6]; // camera-relative world space to each face's clip space, at the capture
    vec4 ProbeFacesParams;      // x: 1 when faces have been captured; yzw: the camera's move since (blocks)
};

const float PROBE_SIZE = 512.0;

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
    vec4 scene = ProbeFacesParams.x > 0.5 ? captured(d) : vec4(0.0);
    fragColor = vec4(scene.rgb + sky * (1.0 - scene.a), 1.0);
}
