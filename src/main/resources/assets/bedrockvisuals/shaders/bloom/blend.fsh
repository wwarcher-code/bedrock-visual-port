#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's BloomBlend: the last upsample added to the HDR scene as + intensity × bloom, before exposure and
// tone mapping. The main image already holds the tone-mapped scene, so this writes a copy of it plus the
// difference bloom makes after the same exposure, grading and tone map. The bloom debug view shows only that
// difference, 20 times over, on black.

uniform sampler2D Source;  // the bloom, half resolution
uniform sampler2D Scene;   // the HDR scene after local exposure
uniform sampler2D Main;    // a copy of the main image

#include <bedrockvisuals:common.glsl>

layout(std140) uniform BloomData {
    vec4 BloomParams;  // x: intensity, y: threshold (luminance after exposure), z: exposure
};

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
    vec4 image = texture(Main, texCoord);
    bool debug = abs(DebugParams.w - 2.0) < 0.5;
    vec4 scene = texture(Scene, texCoord);
    if (scene.a <= 0.0) {
        // Not drawn by the lighting pass (Java's own sky): leave it.
        fragColor = debug ? vec4(0.0, 0.0, 0.0, 1.0) : image;
        return;
    }
    vec3 bloom = upsample(Source, texCoord).rgb * BloomParams.x;
    vec3 with = toDisplay(scene.rgb + bloom);
    vec3 without = toDisplay(scene.rgb);
    vec3 added = max(with - without, 0.0);
    fragColor = debug ? vec4(min(added * 20.0, 1.0), 1.0) : vec4(image.rgb + added, image.a);
}
