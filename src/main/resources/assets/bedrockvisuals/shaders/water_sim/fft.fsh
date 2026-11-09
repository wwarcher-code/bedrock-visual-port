#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's WaterSimulation HorizontalFFT and VerticalFFT: one radix-2 Stockham stage along x or y,
// out[x] = in[j + half·b] + W·in[j + half·b + N/2], W = e^(-2πi x/Ns), with Ns the sub-transform size, half = Ns/2,
// b = floor(x/Ns) and j = x mod half; x is the pixel's index (Bedrock's texture
// coordinate times the resolution; at the pixel's centre the stages would not make a Fourier transform).

#include <bedrockvisuals:water_sim.glsl>

uniform sampler2D Source;

layout(location = 0) out vec4 fftOut;

void main() {
    float n = SimParams0.z;
    float subSize = SimParams2.x;
    bool horizontal = SimParams2.y > 0.5;
    vec2 pixel = floor(gl_FragCoord.xy);
    float x = horizontal ? pixel.x : pixel.y;
    float other = horizontal ? pixel.y : pixel.x;
    float halfSize = subSize * 0.5;
    float block = floor(x / subSize);
    float first = x - floor(x / halfSize) * halfSize + halfSize * block;
    float second = first + n * 0.5;
    ivec2 a = horizontal ? ivec2(first, other) : ivec2(other, first);
    ivec2 b = horizontal ? ivec2(second, other) : ivec2(other, second);
    vec2 p = texelFetch(Source, a, 0).xy;
    vec2 q = texelFetch(Source, b, 0).xy;
    float angle = x / subSize * -6.283185;
    float s = sin(angle);
    float c = cos(angle);
    fftOut = vec4(p.x - s * q.y + c * q.x, s * q.x + p.y + c * q.y, 0.0, 0.0);
}
