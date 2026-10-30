#version 330
#extension GL_ARB_separate_shader_objects : require

// Shadow-map variant of Sodium's terrain vertex shader. Same vertex format, uniforms and push constants,
// but positioned by the sun's view (ShadowProjection.java) instead of Sodium's camera matrices.

#include <sodium:globals.glsl>
#include <sodium:chunk_vertex.glsl>

layout(location = 0) out vec2 v_TexCoord;

layout(std140) uniform ShadowData {
    mat4 ShadowViewProjection; // camera-relative world space to shadow clip space
};

// Must match the push constants Sodium writes.
#ifdef VULKAN
layout(push_constant) uniform PC {
    vec3 u_RegionOffset;
    int u_CurrentTime;
    uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

vec3 _get_draw_translation(uint pos) {
    // Packing scheme is defined by Sodium's LocalSectionIndex.
    return vec3(uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u)) * vec3(16.0);
}

void main() {
    _vert_init();

    vec3 position = _vert_position + u_RegionOffset + _get_draw_translation(_draw_id);
    gl_Position = ShadowViewProjection * vec4(position, 1.0);
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
}
