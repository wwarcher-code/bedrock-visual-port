#version 330
#extension GL_ARB_separate_shader_objects : require

// One mip of the sky probe (SkyProbe.java): the base convolved with the GGX lobe of that mip's roughness, with
// the view along the reflection (N = V = R), as image-based lighting prefilters its probe. Bedrock samples mip
// 9 × (1 - (1 - r)²) for roughness r (ConvolutionType 1), so mip m holds r = 1 - sqrt(1 - m / 9).

#include <bedrockvisuals:octahedral.glsl>

uniform sampler2D ProbeBase;
layout(std140) uniform ProbeData {
    vec4 ProbeMip; // x: roughness, y: this mip's size in texels, z: samples
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec2 hammersley(int i, int n) {
    uint bits = uint(i);
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0F0u) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return vec2(float(i) / float(n), float(bits) * 2.3283064365386963e-10);
}

void main() {
    vec3 r = octahedralDecode((floor(gl_FragCoord.xy) + 0.5) / ProbeMip.y);
    float roughness = ProbeMip.x;
    if (roughness < 0.001) {
        fragColor = vec4(textureLod(ProbeBase, octahedralEncode(r), 0.0).rgb, 1.0);
        return;
    }
    float a = roughness * roughness;
    vec3 up = abs(r.y) < 0.999 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 tx = normalize(cross(up, r));
    vec3 ty = cross(r, tx);
    int count = int(ProbeMip.z);
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < count; i++) {
        vec2 xi = hammersley(i, count);
        float phi = 6.28318531 * xi.x;
        float cosTheta = sqrt((1.0 - xi.y) / (1.0 + (a * a - 1.0) * xi.y));
        float sinTheta = sqrt(1.0 - cosTheta * cosTheta);
        vec3 h = normalize(tx * (cos(phi) * sinTheta) + ty * (sin(phi) * sinTheta) + r * cosTheta);
        vec3 l = 2.0 * dot(r, h) * h - r;
        float nDotL = dot(r, l);
        if (nDotL > 0.0) {
            sum += textureLod(ProbeBase, octahedralEncode(l), 0.0).rgb * nDotL;
            weight += nDotL;
        }
    }
    fragColor = vec4(sum / max(weight, 1e-4), 1.0);
}
