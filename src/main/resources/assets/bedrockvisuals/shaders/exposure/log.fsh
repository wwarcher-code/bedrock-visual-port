#version 330
#extension GL_ARB_separate_shader_objects : require

// The scene's log luminance at a quarter of the resolution, for building the bilateral grid.

#include <bedrockvisuals:local_exposure.glsl>

uniform sampler2D Source; // the HDR scene

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    // Four bilinear taps cover the 4x4 block this texel stands for.
    vec2 texel = 1.0 / vec2(textureSize(Source, 0));
    vec3 sum = texture(Source, texCoord + texel * vec2(-1.0, -1.0)).rgb + texture(Source, texCoord + texel * vec2(1.0, -1.0)).rgb
            + texture(Source, texCoord + texel * vec2(-1.0, 1.0)).rgb + texture(Source, texCoord + texel * vec2(1.0, 1.0)).rgb;
    fragColor = vec4(logLuminance(sum * 0.25), 0.0, 0.0, 1.0);
}
