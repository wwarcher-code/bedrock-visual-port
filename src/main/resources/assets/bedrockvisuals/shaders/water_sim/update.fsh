#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's WaterSimulation UpdateSpectrum: h(k, t) = h0(k) e^(iωt) + conj-mirror h0(-k) e^(-iωt), with
// ω = sqrt(g|k|) times a hash of k (Bedrock's random dispersion), the phase wrapped to one turn.

#include <bedrockvisuals:water_sim.glsl>

uniform sampler2D Spectrum;

layout(location = 0) out vec4 heightOut;

void main() {
    vec2 uv = floor(gl_FragCoord.xy) / SimParams0.z;
    vec4 h0 = texelFetch(Spectrum, ivec2(gl_FragCoord.xy), 0);
    vec2 k = SimParams0.z * 6.283185 * (uv - 0.5) / SimParams0.y;
    float hash = fract(sin(dot(k, vec2(25.9796, 156.466))) * 43758.55);
    float omega = sqrt(SimParams0.x * length(k)) * hash;
    float phase = omega * SimParams1.w;
    phase -= floor(phase * 0.1591549) * 6.283185;
    float s = sin(phase);
    float c = cos(phase);
    float re = c * (h0.x + h0.z) - h0.y * s + h0.w * s;
    float im = s * h0.x - s * h0.z + c * (h0.w + h0.y);
    heightOut = vec4(re, im, 0.0, 0.0);
}
