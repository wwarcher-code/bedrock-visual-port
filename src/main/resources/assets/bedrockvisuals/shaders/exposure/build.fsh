#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's BuildBilateralGrid, as a gather: the grid is GridDimensions.x × .y cells of the screen, each with
// .z slices along normalized log luminance t = (log2 L - min) / (max - min). Bedrock adds each pixel into the
// two slices either side of t × slices (weights 1 - f and f, slice index clamped to slices - 2), as
// (weight, weight × t). Here each texel (cell x + slice × cells across, cell y) sums the quarter-resolution
// pixels that fall in its cell.

#include <bedrockvisuals:local_exposure.glsl>

uniform sampler2D Source; // log luminance, quarter resolution

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 grid = ivec2(GridDimensions.xy);
    int slices = int(GridDimensions.z);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int slice = texel.x / grid.x;
    ivec2 cell = ivec2(texel.x - slice * grid.x, texel.y);
    ivec2 size = textureSize(Source, 0);
    // The pixels whose cell, floor(cells × (p + 0.5) / size), is this one.
    ivec2 first = ivec2(ceil(vec2(cell) * vec2(size) / vec2(grid) - 0.5));
    ivec2 last = ivec2(ceil(vec2(cell + 1) * vec2(size) / vec2(grid) - 0.5)) - 1;
    first = clamp(first, ivec2(0), size - 1);
    last = clamp(last, ivec2(0), size - 1);
    vec2 sum = vec2(0.0);
    for (int y = first.y; y <= last.y; y++) {
        for (int x = first.x; x <= last.x; x++) {
            float t = (texelFetch(Source, ivec2(x, y), 0).r - LOG_MIN) / (LOG_MAX - LOG_MIN);
            float s = float(slices) * t;
            int low = clamp(int(s), 0, slices - 2);
            float f = clamp(s - float(low), 0.0, 1.0);
            float weight = slice == low ? 1.0 - f : (slice == low + 1 ? f : 0.0);
            sum += vec2(weight, weight * t);
        }
    }
    fragColor = vec4(sum, 0.0, 1.0);
}
