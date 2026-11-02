#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's GaussianDownsample: the log luminance of the quarter-resolution scene, Gaussian-blurred (radius r,
// step s, in its pixels; weight exp(-d² / (r² / 2))), written at a sixteenth of the resolution: the plain
// blur local exposure mixes in. Bedrock takes the log of each colour tap; here the taps are already logs.

#include <bedrockvisuals:local_exposure.glsl>

uniform sampler2D Source; // log luminance, quarter resolution

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    int radius = int(BlurParams.x);
    int stepSize = max(int(BlurParams.y), 1);
    int taps = radius / stepSize;
    float spread = float(radius * radius) * 0.5;
    vec2 texel = 1.0 / vec2(textureSize(Source, 0));
    float sum = 0.0;
    float weights = 0.0;
    for (int y = -taps; y <= taps; y++) {
        for (int x = -taps; x <= taps; x++) {
            vec2 d = vec2(x, y) * float(stepSize);
            float weight = exp(-dot(d, d) / spread);
            vec2 uv = clamp(texCoord + d * texel, vec2(0.0), vec2(1.0));
            sum += texture(Source, uv).r * weight;
            weights += weight;
        }
    }
    fragColor = vec4(sum / weights, 0.0, 0.0, 1.0);
}
