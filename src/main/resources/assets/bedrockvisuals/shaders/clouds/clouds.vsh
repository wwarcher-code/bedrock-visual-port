#version 330
#extension GL_ARB_separate_shader_objects : require

// Java's cloud mesh (core/clouds.vsh: one 12x12x4 cell face per quad, from the CloudFaces buffer), passing on
// what Bedrock's cloud lighting needs instead of Java's fixed face shades: the camera-relative position, the
// position inside the cell, and the face normal.

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

const int FLAG_MASK_DIR = 7;
const int FLAG_INSIDE_FACE = 1 << 4;
const int FLAG_USE_TOP_COLOR = 1 << 5;
const int FLAG_EXTRA_Z = 1 << 6;
const int FLAG_EXTRA_X = 1 << 7;

layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};

uniform isamplerBuffer CloudFaces;

layout(location = 0) out vec3 cloudPosition;   // camera-relative
layout(location = 1) out vec3 cellPosition;    // inside the cell, 0 to CellSize
layout(location = 2) flat out vec3 cloudNormal;

const int packedX = 0xF03CC3;
const int packedY = 0x6666F0;
const int packedZ = 0xC3F066;

vec3 vertex(int index) {
    vec3 pos = vec3(0);
    pos.x = float((packedX >> index) & 1);
    pos.y = float((packedY >> index) & 1);
    pos.z = float((packedZ >> index) & 1);
    return pos;
}

// Bottom, top, north, south, west, east, as in Java's mesh.
const vec3[] normals = vec3[](
    vec3(0, -1, 0), vec3(0, 1, 0), vec3(0, 0, -1), vec3(0, 0, 1), vec3(-1, 0, 0), vec3(1, 0, 0)
);

void main() {
    int quadVertex = gl_VertexIndex % 4;
    int index = (gl_VertexIndex / 4) * 3;

    int cellX = texelFetch(CloudFaces, index).r;
    int cellZ = texelFetch(CloudFaces, index + 1).r;
    int dirAndFlags = texelFetch(CloudFaces, index + 2).r;
    int direction = dirAndFlags & FLAG_MASK_DIR;
    bool isInsideFace = (dirAndFlags & FLAG_INSIDE_FACE) == FLAG_INSIDE_FACE;
    cellX = (cellX << 1) | ((dirAndFlags & FLAG_EXTRA_X) >> 7);
    cellZ = (cellZ << 1) | ((dirAndFlags & FLAG_EXTRA_Z) >> 6);
    vec3 faceVertex = vertex((direction * 4) + (isInsideFace ? 3 - quadVertex : quadVertex));
    vec3 pos = (faceVertex * CellSize) + (vec3(cellX, 0, cellZ) * CellSize) + CloudOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    cloudPosition = pos;
    cellPosition = faceVertex * CellSize;
    // Faces seen from inside a cloud face inwards.
    cloudNormal = isInsideFace ? -normals[direction] : normals[direction];
}
