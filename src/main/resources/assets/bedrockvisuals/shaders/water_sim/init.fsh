#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's WaterSimulation InitSpectrum: the Phillips spectrum's amplitude at each wave vector and its mirror,
// (sqrt P(k), sqrt P(k), sqrt P(-k), -sqrt P(-k)). Bedrock draws no random numbers here.

#include <bedrockvisuals:water_sim.glsl>

layout(location = 0) out vec4 spectrumOut;

float phillips(vec2 k) {
    float kLength = length(k);
    vec2 wind = SimParams1.xy;
    float windSpeed = length(wind);
    if (kLength == 0.0 || windSpeed == 0.0) {
        return 0.0;
    }
    float k2 = kLength * kLength;
    float l = windSpeed * windSpeed / SimParams0.x;
    float alignment = dot(wind / windSpeed, k / kLength);
    float p = exp2(-1.442695 / (l * l * k2)) * 1e-5 / (k2 * k2) * alignment * alignment;
    p *= exp2(SimParams0.w * -1.442695 * k2);
    p *= alignment < 0.0 ? SimParams1.z : 1.0;
    return p * 0.5;
}

void main() {
    vec2 uv = floor(gl_FragCoord.xy) / SimParams0.z;
    vec2 k = SimParams0.z * 6.283185 * (uv - 0.5) / SimParams0.y;
    float a = sqrt(phillips(k));
    float b = sqrt(phillips(-k));
    spectrumOut = vec4(a, a, b, -b);
}
