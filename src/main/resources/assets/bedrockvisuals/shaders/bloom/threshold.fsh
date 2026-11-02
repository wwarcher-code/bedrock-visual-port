#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's Bloom ThresholdedDownSample: half the centre tap plus an eighth of four diagonal taps 3 source
// texels out, each kept only if its luminance reaches the threshold.

uniform sampler2D Source;  // the lighting pass's HDR colour, before exposure

layout(std140) uniform BloomData {
    vec4 BloomParams;  // x: intensity, y: threshold (luminance after exposure), z: exposure
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

vec4 kept(vec2 uv) {
    vec4 c = texture(Source, uv);
    return dot(c.rgb, LUMA) * BloomParams.z < BloomParams.y ? vec4(0.0) : c;
}

void main() {
    vec2 offset = 3.0 / vec2(textureSize(Source, 0));
    vec4 sum = kept(texCoord) * 0.5;
    sum += (kept(texCoord + offset) + kept(texCoord + vec2(-offset.x, offset.y))
            + kept(texCoord + vec2(offset.x, -offset.y)) + kept(texCoord - offset)) * 0.125;
    fragColor = sum;
}
