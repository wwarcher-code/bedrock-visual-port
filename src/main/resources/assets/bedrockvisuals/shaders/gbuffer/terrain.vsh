#version 330
#extension GL_ARB_separate_shader_objects : require

// G-buffer variant of Sodium's block_layer_opaque vertex shader. It takes Sodium's vertex format, uniforms
// and push constants unchanged, but hands the fragment shader the raw vertex color (no lightmap), the light
// levels and the camera-relative position instead of a lit color and fog distance.

#include <sodium:globals.glsl>
#include <sodium:chunk_vertex.glsl>

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;
layout(location = 2) out vec2 v_LightCoord;
layout(location = 3) out vec3 v_Position;

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
    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

    v_Color = _vert_color;
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
    v_LightCoord = _vert_tex_light_coord;
    v_Position = position;
}
