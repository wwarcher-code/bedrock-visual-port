// Bedrock's volumetric fog grid (froxels), as its PopulateVolume and VolumeScattering lay it out: 240 x 135 cells
// across the screen and 48 slices in depth (VolumeDimensions in GPU captures), from 0.025 to 250 blocks of view
// depth (VolumeNearFar), the slices spread as f = (2^(5.77078 s) - 1) x 0.01865736 = (e^(4s) - 1) / (e^4 - 1).
// Here the 48 slices lie side by side in one 2D texture, 8 across and 6 down (FroxelVolume.java): each texel
// holds the light scattered towards the camera from the camera to that slice's centre (rgb) and the
// transmittance over the same stretch (in the other texture).

#ifndef BEDROCKVISUALS_FROXELS
#define BEDROCKVISUALS_FROXELS

const ivec2 FROXEL_CELLS = ivec2(240, 135);
const int FROXEL_SLICES = 48;
const int FROXEL_TILES_X = 8;
const vec2 FROXEL_ATLAS = vec2(240.0 * 8.0, 135.0 * 6.0);
const float FROXEL_NEAR = 0.025;
const float FROXEL_FAR = 250.0;

// View depth at slice coordinate s (0 at the near plane, 1 at the far one).
float froxelDepth(float s) {
    float f = (exp2(s * 5.77078) - 1.0) * 0.01865736;
    return mix(FROXEL_NEAR, FROXEL_FAR, f);
}

// The inverse: slice coordinate (0 to 1) of view depth z.
float froxelSlice(float z) {
    float f = clamp((z - FROXEL_NEAR) / (FROXEL_FAR - FROXEL_NEAR), 0.0, 1.0);
    return log2(1.0 + f / 0.01865736) / 5.77078;
}

// The camera's forward direction, camera-relative, from the inverse view-projection.
vec3 froxelForward(mat4 inverseViewProjection) {
    vec4 p = inverseViewProjection * vec4(0.0, 0.0, 0.5, 1.0);
    return normalize(p.xyz / p.w);
}

// Atlas coordinates of screen position uv (the lighting pass's, where ndc = uv * 2 - 1) in slice j, kept half a
// texel inside the slice's tile so bilinear filtering doesn't reach into the next tile.
vec2 froxelAtlasUv(vec2 uv, int j) {
    vec2 cells = vec2(FROXEL_CELLS);
    vec2 origin = vec2(float(j % FROXEL_TILES_X), float(j / FROXEL_TILES_X)) * cells;
    return (origin + clamp(uv * cells, vec2(0.5), cells - 0.5)) / FROXEL_ATLAS;
}

// The fog between the camera and view depth viewZ along screen position uv: in-scattered light (returned) and
// transmittance (out), interpolated between the slices around that depth, as Bedrock's apply passes read it.
vec3 froxelFog(sampler2D scatter, sampler2D transmittanceMap, vec2 uv, float viewZ, out vec3 transmittance) {
    float sl = froxelSlice(viewZ) * float(FROXEL_SLICES) - 0.5;
    if (sl < 0.0) {
        // In front of the first slice's centre: from nothing at the camera up to it.
        float share = clamp(viewZ / froxelDepth(0.5 / float(FROXEL_SLICES)), 0.0, 1.0);
        vec2 at = froxelAtlasUv(uv, 0);
        transmittance = mix(vec3(1.0), texture(transmittanceMap, at).rgb, share);
        return texture(scatter, at).rgb * share;
    }
    int j0 = min(int(floor(sl)), FROXEL_SLICES - 1);
    int j1 = min(j0 + 1, FROXEL_SLICES - 1);
    float w = j0 == j1 ? 0.0 : fract(sl);
    vec2 a = froxelAtlasUv(uv, j0);
    vec2 b = froxelAtlasUv(uv, j1);
    transmittance = mix(texture(transmittanceMap, a).rgb, texture(transmittanceMap, b).rgb, w);
    return mix(texture(scatter, a).rgb, texture(scatter, b).rgb, w);
}

// As froxelFog, at one cell's centre (uv on it), for a depth whose stretch from the slice centre before it lies
// in air while the next slice's centre is in water: the camera looking at a water surface. Interpolated towards
// the next centre, the water beyond the surface leaked into the haze above it, more or less by where the
// surface fell between two centres: bands at the slices' spacing across distant water. Extended from the
// stretch before instead, which is air.
vec3 froxelFogInAir(sampler2D scatter, sampler2D transmittanceMap, vec2 uv, float viewZ, out vec3 transmittance) {
    float sl = froxelSlice(viewZ) * float(FROXEL_SLICES) - 0.5;
    if (sl < 1.0 || sl >= float(FROXEL_SLICES - 1)) {
        return froxelFog(scatter, transmittanceMap, uv, viewZ, transmittance);
    }
    int j0 = int(floor(sl));
    vec2 a = froxelAtlasUv(uv, j0 - 1);
    vec2 b = froxelAtlasUv(uv, j0);
    float za = froxelDepth((float(j0) - 0.5) / float(FROXEL_SLICES));
    float zb = froxelDepth((float(j0) + 0.5) / float(FROXEL_SLICES));
    float k = (viewZ - zb) / max(zb - za, 1e-4);
    vec3 ta = texture(transmittanceMap, a).rgb;
    vec3 tb = texture(transmittanceMap, b).rgb;
    transmittance = tb * pow(clamp(tb / max(ta, vec3(1e-4)), vec3(0.0), vec3(1.0)), vec3(k));
    vec3 sb = texture(scatter, b).rgb;
    return sb + max(sb - texture(scatter, a).rgb, vec3(0.0)) * k;
}

#endif
