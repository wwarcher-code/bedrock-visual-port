#version 330
#extension GL_ARB_separate_shader_objects : require

// The offline export's sub-frame accumulation (OfflineRender.java): the running average of the sub-frames drawn
// so far, mix(average, this sub-frame, 1 / (i + 1)); and, at the end, the average written back (weight 0).

uniform sampler2D Current; // this sub-frame, as the level left it
uniform sampler2D Average; // the average of the sub-frames before it

layout(std140) uniform AccumulateData {
    vec4 AccumulateParams; // x: this sub-frame's weight
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec4 average = texelFetch(Average, pixel, 0);
    vec4 current = texelFetch(Current, pixel, 0);
    fragColor = mix(average, current, AccumulateParams.x);
}
