#version 330
#extension GL_ARB_separate_shader_objects : require

// The scene probe's variant of the G-buffer's vertex shader (gbuffer/terrain.vsh): the same outputs, but
// positioned by one face of the probe's cube (SceneProbe.java) instead of Sodium's camera matrices (Sodium
// uploads those once a frame, so a second view through them would be ignored).

#include <sodium:globals.glsl>
#include <sodium:chunk_vertex.glsl>

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;
layout(location = 2) out vec2 v_LightCoord;
layout(location = 3) out vec3 v_Position;

layout(std140) uniform ProbeFace {
    mat4 ProbeViewProjection; // camera-relative world space to this face's clip space
    mat4 ProbeInverseViewProjection;
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
    gl_Position = ProbeViewProjection * vec4(position, 1.0);

    v_Color = _vert_color;
    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
    v_LightCoord = _vert_tex_light_coord;
    v_Position = position;
}
