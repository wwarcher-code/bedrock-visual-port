#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's SSRFillGaps with ExtendedGapFill on (ReflectionPass.java): a pixel the march missed (alpha below 0)
// takes the average of the hits above and below it, looking two pixels away where the nearest is missed too, if
// both sides hit. Pixels with no water (-2) and hits pass through.

uniform sampler2D March;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fillOut;

void main() {
    vec4 here = texture(March, texCoord);
    fillOut = here;
    if (here.w >= 0.0 || here.w < -1.5) {
        return;
    }
    float step = 1.0 / float(textureSize(March, 0).y);
    vec4 up = texture(March, texCoord + vec2(0.0, step));
    if (up.w < 0.0) {
        up = texture(March, texCoord + vec2(0.0, 2.0 * step));
    }
    vec4 down = texture(March, texCoord - vec2(0.0, step));
    if (down.w < 0.0) {
        down = texture(March, texCoord - vec2(0.0, 2.0 * step));
    }
    if (up.w >= 0.0 && down.w >= 0.0) {
        fillOut = vec4((up.xy + down.xy) * 0.5, here.z, (up.w + down.w) * 0.5);
    }
}
