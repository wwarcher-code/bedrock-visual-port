#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's Bloom DFUpSample (dual filter): four diagonal taps 1 source texel out at 0.166 each and four
// 2 texels out at 0.083 each.

uniform sampler2D Source;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec4 upsample(sampler2D source, vec2 uv) {
    vec2 near = 1.0 / vec2(textureSize(source, 0));
    vec2 far = 2.0 * near;
    vec4 sum = (texture(source, uv + near) + texture(source, uv + vec2(-near.x, near.y))
            + texture(source, uv + vec2(near.x, -near.y)) + texture(source, uv - near)) * 0.166;
    sum += (texture(source, uv + far) + texture(source, uv + vec2(-far.x, far.y))
            + texture(source, uv + vec2(far.x, -far.y)) + texture(source, uv - far)) * 0.083;
    return sum;
}

void main() {
    fragColor = upsample(Source, texCoord);
}
