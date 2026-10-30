#version 460 core

// Depth only: cut-out texels (leaves, grass) are discarded so they cast shadows of their actual shape.

#include <sodium:globals.glsl>

layout(location = 0) in vec2 v_TexCoord;

uniform sampler2D u_BlockTex;

void main() {
#ifdef ALPHA_CUTOUT
    if (texture(u_BlockTex, v_TexCoord).a < ALPHA_CUTOUT) {
        discard;
    }
#endif
}
