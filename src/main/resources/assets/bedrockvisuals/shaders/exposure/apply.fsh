#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's ApplyLocalExposure. With L the pixel's log luminance, A the log average, D the blurred log
// luminance and B the bilateral grid's mean log luminance at (pixel, L):
//   base = grid empty (weight < 0.001) ? D : mix(B, D, z)
//   scene × 2^((A - L) + y (L - base) + x (base - A))
// With Bedrock's (x, y, z) = (0.75, 1, 0.4) that keeps detail and pulls each region's level a quarter of the
// way towards the average. The main image already holds the tone-mapped scene (plus Java's sun, moon and
// stars), so a copy of it is multiplied by how much the tone-mapped result changes and written back; the
// locally exposed HDR scene goes on to the bloom.

#include <bedrockvisuals:common.glsl>
#define LOCAL_EXPOSURE_APPLY
#include <bedrockvisuals:local_exposure.glsl>

uniform sampler2D Scene;  // the lighting pass's HDR colour
uniform sampler2D Main;   // a copy of the main image

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 exposed;

vec4 localExposureRatio(vec4 scene, out vec4 exposed);

void main() {
    vec4 scene = texture(Scene, texCoord);
    vec4 image = texture(Main, texCoord);
    vec4 ratio = localExposureRatio(scene, exposed);
    fragColor = vec4(image.rgb * ratio.rgb, image.a);
}

vec4 localExposureRatio(vec4 scene, out vec4 exposed) {
    vec4 ratio;
    if (scene.a <= 0.0) {
        // Not drawn by the lighting pass. (Debug view: red 0, green 1, blue 0 times the grey.)
        ratio = abs(DebugParams.w - 1.0) < 0.5 ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0);
        exposed = scene;
        return ratio;
    }
    float gridWeight;
    float change = localExposureChange(scene.rgb, texCoord, gridWeight);
    vec3 color = scene.rgb * exp2(change);
    exposed = vec4(color, scene.a);
    vec3 before = toDisplay(scene.rgb);
    vec3 after = toDisplay(color);
    ratio = vec4(mix(vec3(1.0), clamp(after / max(before, vec3(1e-4)), 0.0, 16.0), step(vec3(1e-4), before)), 1.0);
    if (abs(DebugParams.w - 1.0) < 0.5) {
        // The debug view: the lighting pass drew mid-grey; times red 2 (this branch), green the factor
        // (capped at 2), blue 2 where the grid has data there.
        ratio = vec4(2.0, clamp(exp2(change), 0.0, 2.0), gridWeight >= 0.001 ? 2.0 : 0.0, 1.0);
    }
    return ratio;
}
