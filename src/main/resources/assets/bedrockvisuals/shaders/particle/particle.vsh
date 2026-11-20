#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock-lit particles (render/ParticlePass.java): Minecraft's particle vertex (core/particle), feeding the
// entities' fragment shader (entity/entity.fsh, built with PARTICLE) the outputs entity.vsh gives it: the
// camera-relative position, a normal towards the camera (particles face it), the colour, the block and sky light
// levels. Particles were drawn with Minecraft's lightmap into the graded image: lava's smoke nearly black at
// night beside the bright lava, as nothing else in the frame was lit that way.

#include <bedrockvisuals:common.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Color;
layout(location = 3) in ivec2 UV2;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 4) out vec4 lightMapColor;
layout(location = 5) out vec4 overlayColor;
layout(location = 6) out vec2 texCoord0;
layout(location = 8) out vec3 bvPosition;
layout(location = 9) out vec3 bvNormal;
layout(location = 10) out vec4 bvColor;
layout(location = 11) out vec2 bvLight;
layout(location = 12) out vec4 bvClip;

vec3 bvWorld(vec4 clip) {
    vec4 world = InverseViewProjection * clip;
    return world.xyz / world.w;
}

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    bvPosition = bvWorld(gl_Position);
    bvNormal = normalize(-bvPosition + vec3(1e-6));
    bvColor = Color;
    bvLight = clamp(vec2(UV2) / 240.0, 0.0, 1.0);
    bvClip = gl_Position;
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color;
    // Kept as Minecraft binds it (the fragment shader only reads it on a never-taken branch).
    lightMapColor = sample_lightmap(Sampler2, UV2);
    // No hurt flash: alpha 1 keeps the texel's colour.
    overlayColor = vec4(0.0, 0.0, 0.0, 1.0);
    texCoord0 = UV0;
}
