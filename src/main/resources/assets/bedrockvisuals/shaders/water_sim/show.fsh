#version 330
#extension GL_ARB_separate_shader_objects : require

// The simulation's height field for the debug view: grey around mid, brighter for crests, darker for troughs, on a
// log scale (at the material's default properties the waves are some 1e-11 blocks high; Bedrock draws nothing
// from it).

uniform sampler2D Height;

layout(location = 0) out vec4 showOut;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    // The spectrum has its zero frequency in the middle, which leaves every other texel's sign flipped.
    float h = texelFetch(Height, pixel, 0).x * (((pixel.x + pixel.y) & 1) == 0 ? 1.0 : -1.0);
    float magnitude = clamp((log2(max(abs(h), 1e-30)) + 46.0) / 16.0, 0.0, 1.0); // 2^-46 to 2^-30
    float v = 0.5 + 0.5 * sign(h) * magnitude;
    showOut = vec4(vec3(v), 1.0);
}
