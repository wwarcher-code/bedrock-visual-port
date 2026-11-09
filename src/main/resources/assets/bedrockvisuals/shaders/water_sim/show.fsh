#version 330
#extension GL_ARB_separate_shader_objects : require

// The simulation's height field for the debug view: grey around mid, brighter for crests, softly normalised
// (Bedrock draws nothing from it).

uniform sampler2D Height;

layout(location = 0) out vec4 showOut;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    // The spectrum has its zero frequency in the middle, which leaves every other texel's sign flipped.
    float h = texelFetch(Height, pixel, 0).x * (((pixel.x + pixel.y) & 1) == 0 ? 1.0 : -1.0);
    float v = 0.5 + 0.5 * h / (abs(h) + 0.02);
    showOut = vec4(vec3(v), 1.0);
}
