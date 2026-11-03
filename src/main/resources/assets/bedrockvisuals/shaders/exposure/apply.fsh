#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's ApplyLocalExposure. With L the pixel's log luminance, A the log average, D the blurred log
// luminance and B the bilateral grid's mean log luminance at (pixel, L):
//   base = grid empty (weight < 0.001) ? D : mix(B, D, z)
//   scene × 2^((A - L) + y (L - base) + x (base - A))
// With Bedrock's (x, y, z) = (0.75, 1, 0.4) that keeps detail and pulls each region's level a quarter of the
// way towards the average. The main image already holds the tone-mapped scene (plus Java's sun, moon and
// stars), so it's multiplied by how much the tone-mapped result changes; the locally exposed HDR scene goes on
// to the bloom.

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:local_exposure.glsl>

uniform sampler2D Scene;  // the lighting pass's HDR colour
uniform sampler2D Blurred;
uniform sampler2D Grid;   // the filtered grid: slices side by side

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 ratio;
layout(location = 1) out vec4 exposed;

vec2 gridAt(vec2 uv, int slice) {
    vec2 grid = GridDimensions.xy;
    vec2 cell = clamp(uv * grid, vec2(0.5), grid - 0.5);
    return texture(Grid, vec2((float(slice) * grid.x + cell.x) / (grid.x * GridDimensions.z), cell.y / grid.y)).rg;
}

void main() {
    vec4 scene = texture(Scene, texCoord);
    if (scene.a <= 0.0) {
        // Not drawn by the lighting pass. (Debug view: red 0, green 1, blue 0 times the grey.)
        ratio = DebugParams.w > 0.5 ? vec4(0.0, 0.5, 0.0, 1.0) : vec4(1.0);
        exposed = scene;
        return;
    }
    float l = logLuminance(scene.rgb);
    float d = texture(Blurred, texCoord).r;
    float t = (l - LOG_MIN) / (LOG_MAX - LOG_MIN);
    int slices = int(GridDimensions.z);
    float s = t * float(slices) - 0.5;
    int low = clamp(int(floor(s)), 0, slices - 1);
    int high = clamp(int(floor(s)) + 1, 0, slices - 1);
    vec2 g = mix(gridAt(texCoord, low), gridAt(texCoord, high), clamp(s - floor(s), 0.0, 1.0));
    float base = d;
    if (g.x >= 0.001) {
        base = mix(LOG_MIN + (LOG_MAX - LOG_MIN) * (g.y / g.x), d, LocalExposureParams.z);
    }
    float a = LocalExposureParams.w;
    float change = (a - l) + LocalExposureParams.y * (l - base) + LocalExposureParams.x * (base - a);
    vec3 color = scene.rgb * exp2(change);
    exposed = vec4(color, scene.a);
    vec3 before = toDisplay(scene.rgb);
    vec3 after = toDisplay(color);
    ratio = vec4(mix(vec3(1.0), clamp(after / max(before, vec3(1e-4)), 0.0, 16.0), step(vec3(1e-4), before)), 1.0);
    if (DebugParams.w > 0.5) {
        // The debug view, written without blending: mid-grey times red 2 (this branch), green the factor
        // (capped at 2), blue 2 where the grid has data there.
        ratio = vec4(1.0, 0.5 * clamp(exp2(change), 0.0, 2.0), g.x >= 0.001 ? 1.0 : 0.0, 1.0);
    }
}
