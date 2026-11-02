#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's FilterBilateralGrid: within each luminance slice, a Gaussian over the cells within the radius R
// (clamped to the grid), weight exp(-d² / (R² / 2)), normalized by the weights.

#include <bedrockvisuals:local_exposure.glsl>

uniform sampler2D Source; // the built grid

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 grid = ivec2(GridDimensions.xy);
    int radius = max(int(GridDimensions.w), 1);
    float spread = float(radius * radius) * 0.5;
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int slice = texel.x / grid.x;
    ivec2 cell = ivec2(texel.x - slice * grid.x, texel.y);
    ivec2 first = clamp(cell - radius, ivec2(0), grid - 1);
    ivec2 last = clamp(cell + radius, ivec2(0), grid - 1);
    vec3 sum = vec3(0.0);
    for (int y = first.y; y <= last.y; y++) {
        for (int x = first.x; x <= last.x; x++) {
            vec2 d = vec2(x - cell.x, y - cell.y);
            float weight = exp(-dot(d, d) / spread);
            sum += vec3(texelFetch(Source, ivec2(x + slice * grid.x, y), 0).rg * weight, weight);
        }
    }
    fragColor = vec4(sum.xy / sum.z, 0.0, 1.0);
}
