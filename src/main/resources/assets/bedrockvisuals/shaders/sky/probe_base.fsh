#version 330
#extension GL_ARB_separate_shader_objects : require

// The sky probe's base (SkyProbe.java): Bedrock's sky radiance (the function its SkyProbeDeferredShading lights
// the probe with) in every direction of an octahedral map, in this renderer's units.

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>

const float PROBE_SIZE = 512.0;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 d = octahedralDecode((floor(gl_FragCoord.xy) + 0.5) / PROBE_SIZE);
    vec3 sky = bedrockSkyRadiance(d, normalize(SunDirection.xyz), normalize(MoonDirection.xyz)) * AtmosphereHorizon.w;
    fragColor = vec4(sky, 1.0);
}
