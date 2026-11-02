#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's Bloom DFDownSample (dual filter): half the centre tap plus an eighth of four diagonal taps 3
// source texels out.

uniform sampler2D Source;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 offset = 3.0 / vec2(textureSize(Source, 0));
    vec4 sum = texture(Source, texCoord) * 0.5;
    sum += (texture(Source, texCoord + offset) + texture(Source, texCoord + vec2(-offset.x, offset.y))
            + texture(Source, texCoord + vec2(offset.x, -offset.y)) + texture(Source, texCoord - offset)) * 0.125;
    fragColor = sum;
}
