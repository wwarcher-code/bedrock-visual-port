#version 330
#extension GL_ARB_separate_shader_objects : require

// Water variant of Sodium's terrain vertex shader, used for the translucent layer (all of Minecraft's OIT stages).
// Everything Sodium outputs is unchanged; it also hands the fragment shader the raw light levels and the
// camera-relative position, which the Bedrock water shading needs.

#include <sodium:globals.glsl>
#include <sodium:fog.glsl>
#include <sodium:chunk_vertex.glsl>

layout(location = 0) out vec4 v_Color;
layout(location = 1) out vec2 v_TexCoord;

#ifdef USE_FOG
layout(location = 2) out vec2 v_FragDistance;
layout(location = 3) out float fadeFactor;
#endif

layout(location = 4) out vec2 v_LightCoord;
layout(location = 5) out vec3 v_Position;
// The provoking vertex's texture coordinate, unchanged across the face: which sprite the face shows. Tested
// per fragment from the interpolated coordinate, a row of pixels where interpolation landed a hair outside the
// water sprite's rectangle was taken for some other translucent block and drawn as plain, unlit texture (a thin
// line across the water at a fixed distance, and specks).
layout(location = 6) flat out vec2 v_FaceTexCoord;

uniform isamplerBuffer u_SectionTimeInfo;

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

#ifndef OIT_ALPHA_ONLY
uniform sampler2D u_LightTex; // The light map texture sampler
#endif

uvec3 _get_relative_chunk_coord(uint pos) {
    // Packing scheme is defined by LocalSectionIndex
    return uvec3(pos) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

vec3 _get_draw_translation(uint pos) {
    return _get_relative_chunk_coord(pos) * vec3(16.0);
}

void main() {
    _vert_init();

    vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
    vec3 position = _vert_position + translation;

#ifdef USE_FOG
    v_FragDistance = getFragDistance(position);

    int chunkId = int(_draw_id);
    int chunkFade = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(chunkId))).r;
    float fade = clamp(float(u_CurrentTime - chunkFade) * u_FadePeriodInv, 0.0, 1.0);
    fadeFactor = (chunkFade < 0) ? 1.0 : fade;
#endif

    gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);

#ifndef OIT_ALPHA_ONLY
    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);
#else
    v_Color = _vert_color;
#endif

    v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink) + _vert_tex_diffuse_coord;
    v_FaceTexCoord = v_TexCoord;
    v_LightCoord = _vert_tex_light_coord;
    v_Position = position;
}
