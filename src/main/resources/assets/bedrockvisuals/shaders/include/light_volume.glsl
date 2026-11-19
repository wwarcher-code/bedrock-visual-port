// The sky and block light around the camera, block by block (LightVolume.java): what Bedrock's volumetric fog
// reads from its SkyAmbientSamples texture per froxel. Needs LIGHT_VOLUME, the volume's sampler, and
// LightingData (common.glsl).

#ifndef BEDROCKVISUALS_LIGHT_VOLUME
#define BEDROCKVISUALS_LIGHT_VOLUME

// How far from the camera the volume can be read, horizontally (x) and vertically (y): its half size less the
// margins LightVolume.java keeps for the camera's travel since each slice was written (LightVolumeOrigin.w: 0
// when the volume isn't there, else 1 + horizontal margin + 64 × vertical margin).
vec2 lightVolumeRange() {
    float margins = max(LightVolumeOrigin.w - 1.0, 0.0);
    float vertical = floor(margins / 64.0 + 0.001);
    return vec2(63.0 - (margins - vertical * 64.0), 23.0 - vertical);
}

// Whether the camera-relative point p lies in the volume.
bool inLightVolume(vec3 p) {
    vec2 range = lightVolumeRange();
    return LightVolumeOrigin.w > 0.5 && max(abs(p.x), abs(p.z)) <= range.x && abs(p.y) <= range.y;
}

// Sky light (x) and block light (y), 0 to 1, at the camera-relative point p; negative outside the volume.
vec2 lightVolumeAt(vec3 p) {
    if (!inLightVolume(p)) {
        return vec2(-1.0);
    }
    ivec3 cell = ivec3(floor(p + LightVolumeOrigin.xyz));
    cell = ivec3(int(mod(float(cell.x), 128.0)), int(mod(float(cell.y), 48.0)), int(mod(float(cell.z), 128.0)));
    ivec2 texel = ivec2((cell.y % 8) * 128 + cell.x, (cell.y / 8) * 128 + cell.z);
    return texelFetch(LIGHT_VOLUME, texel, 0).rg;
}

// Whether the block at the camera-relative point p gives off light while barely glowing in Bedrock's maps, its
// whole surface standing in for its glow (lava; LightVolume's blue at 64 or 192); false outside the volume.
bool glowsAllOver(vec3 p) {
    if (!inLightVolume(p)) {
        return false;
    }
    ivec3 cell = ivec3(floor(p + LightVolumeOrigin.xyz));
    cell = ivec3(int(mod(float(cell.x), 128.0)), int(mod(float(cell.y), 48.0)), int(mod(float(cell.z), 128.0)));
    ivec2 texel = ivec2((cell.y % 8) * 128 + cell.x, (cell.y / 8) * 128 + cell.z);
    float blue = texelFetch(LIGHT_VOLUME, texel, 0).b * 255.0;
    return abs(blue - 64.0) < 20.0 || abs(blue - 192.0) < 20.0;
}

// Whether the block at world-grid cell (camera-relative, floored) is solid and opaque.
bool solidCell(ivec3 cameraCell) {
    ivec3 cell = cameraCell + ivec3(floor(LightVolumeOrigin.xyz));
    cell = ivec3(int(mod(float(cell.x), 128.0)), int(mod(float(cell.y), 48.0)), int(mod(float(cell.z), 128.0)));
    ivec2 texel = ivec2((cell.y % 8) * 128 + cell.x, (cell.y / 8) * 128 + cell.z);
    return texelFetch(LIGHT_VOLUME, texel, 0).b > 0.5;
}

// The point lights' shadows (Bedrock's PointLightShadowTextureAtlas: each light's six cube faces of depth, one
// texel compared, hard edged): here the blocks between camera-relative points from and to are walked (Amanda
// and Woo's grid traversal) and the light is blocked by the first solid one, the light's own block excepted.
// 1 lit, 0 shadowed, -1 when the path leaves the volume.
float voxelVisibility(vec3 from, vec3 to) {
    if (!inLightVolume(from) || !inLightVolume(to)) {
        return -1.0;
    }
    vec3 origin = from + fract(LightVolumeOrigin.xyz);
    vec3 target = to + fract(LightVolumeOrigin.xyz);
    vec3 d = target - origin;
    ivec3 cell = ivec3(floor(origin));
    ivec3 last = ivec3(floor(target));
    ivec3 stepDir = ivec3(sign(d));
    vec3 inv = 1.0 / max(abs(d), vec3(1e-6));
    vec3 tMax = (vec3(stepDir) * (vec3(cell) + 0.5 - origin) + 0.5) * inv;
    vec3 tDelta = inv;
    for (int i = 0; i < 48; i++) {
        if (cell == last) {
            return 1.0;
        }
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            cell.x += stepDir.x;
            tMax.x += tDelta.x;
        } else if (tMax.y < tMax.z) {
            cell.y += stepDir.y;
            tMax.y += tDelta.y;
        } else {
            cell.z += stepDir.z;
            tMax.z += tDelta.z;
        }
        if (cell != last && solidCell(cell)) {
            return 0.0;
        }
    }
    return 1.0;
}

// Whether the block at world-grid cell (camera-relative, floored) lies under its column's terrain surface.
bool coveredCell(ivec3 cameraCell) {
    ivec3 cell = cameraCell + ivec3(floor(LightVolumeOrigin.xyz));
    cell = ivec3(int(mod(float(cell.x), 128.0)), int(mod(float(cell.y), 48.0)), int(mod(float(cell.z), 128.0)));
    ivec2 texel = ivec2((cell.y % 8) * 128 + cell.x, (cell.y / 8) * 128 + cell.z);
    return texelFetch(LIGHT_VOLUME, texel, 0).a > 0.5;
}

// Whether a ray from the camera-relative point from along direction (unit) meets a solid block before it leaves
// the volume or has crossed maxCells blocks: 0 blocked, 1 clear. A ray that leaves the volume (or runs out of
// steps) still under the terrain's surface counts as blocked: the volume reaches only 23 blocks up and down, and
// in a cave taller than that the rays from its floor ran out before they met its roof (sunlit patches deep in
// big caves).
float voxelRayVisibility(vec3 from, vec3 direction, int maxCells) {
    if (!inLightVolume(from)) {
        return 1.0;
    }
    vec3 origin = from + fract(LightVolumeOrigin.xyz);
    ivec3 cell = ivec3(floor(origin));
    ivec3 stepDir = ivec3(sign(direction));
    vec3 inv = 1.0 / max(abs(direction), vec3(1e-6));
    vec3 tMax = (vec3(stepDir) * (vec3(cell) + 0.5 - origin) + 0.5) * inv;
    vec2 range = lightVolumeRange();
    ivec3 inside = cell;
    for (int i = 0; i < maxCells; i++) {
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            cell.x += stepDir.x;
            tMax.x += inv.x;
        } else if (tMax.y < tMax.z) {
            cell.y += stepDir.y;
            tMax.y += inv.y;
        } else {
            cell.z += stepDir.z;
            tMax.z += inv.z;
        }
        vec3 p = vec3(cell) + 0.5 - fract(LightVolumeOrigin.xyz);
        if (max(abs(p.x), abs(p.z)) > range.x || abs(p.y) > range.y) {
            return coveredCell(inside) ? 0.0 : 1.0;
        }
        if (solidCell(cell)) {
            return 0.0;
        }
        inside = cell;
    }
    return coveredCell(inside) ? 0.0 : 1.0;
}

#endif
