#version 460 core

// Writes one opaque terrain fragment into the G-buffer. Target layout is documented in GBuffer.java.

#include <sodium:globals.glsl>

layout(location = 0) in vec4 v_Color;
layout(location = 1) in vec2 v_TexCoord;
layout(location = 2) in vec2 v_LightCoord;
layout(location = 3) in vec3 v_Position;

uniform sampler2D u_BlockTex;

layout(location = 0) out vec4 out_Albedo;
layout(location = 1) out vec4 out_Normal;
layout(location = 2) out vec4 out_Material;
layout(location = 3) out vec4 out_Light;

// Minecraft darkens each face by direction (CardinalLighting.DEFAULT, the overworld's): top 1.0, bottom 0.5,
// north and south 0.8, east and west 0.6. Sodium bakes it into the vertex color; the lighting pass shades
// by the real normal instead, so it is divided back out. Only axis-aligned faces carry it, and the Nether's
// values (0.9 top and bottom) aren't handled yet.
float faceShade(vec3 normal) {
    vec3 a = abs(normal);
    if (a.y > 0.999) {
        return normal.y > 0.0 ? 1.0 : 0.5;
    }
    if (a.z > 0.999) {
        return 0.8;
    }
    if (a.x > 0.999) {
        return 0.6;
    }
    return 1.0;
}

void main() {
    vec4 color = texture(u_BlockTex, v_TexCoord) * v_Color;

#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif

    // Flat face normal from the position's screen-space derivatives. Its sign depends on the backend's
    // screen orientation, so flip it to face the camera, which sits at the origin of v_Position.
    vec3 normal = normalize(cross(dFdx(v_Position), dFdy(v_Position)));
    if (dot(normal, v_Position) > 0.0) {
        normal = -normal;
    }

    out_Albedo = vec4(min(color.rgb / faceShade(normal), vec3(1.0)), 1.0);
    out_Normal = vec4(normal * 0.5 + 0.5, 1.0);
    // Bedrock's PBR fallback for blocks until texture-set materials arrive in step 4: fully rough,
    // non-metallic, non-emissive, no subsurface.
    out_Material = vec4(0.0, 0.0, 1.0, 0.0);
    out_Light = vec4(v_LightCoord, 0.0, 1.0);
}
