// The sky and block light around the camera, block by block (LightVolume.java): what Bedrock's volumetric fog
// reads from its SkyAmbientSamples texture per froxel. Needs LIGHT_VOLUME, the volume's sampler, and
// LightingData (common.glsl).

#ifndef BEDROCKVISUALS_LIGHT_VOLUME
#define BEDROCKVISUALS_LIGHT_VOLUME

// Sky light (x) and block light (y), 0 to 1, at the camera-relative point p; negative outside the volume.
vec2 lightVolumeAt(vec3 p) {
    if (LightVolumeOrigin.w < 0.5 || max(abs(p.x), abs(p.z)) > 63.0 || abs(p.y) > 23.0) {
        return vec2(-1.0);
    }
    ivec3 cell = ivec3(floor(p + LightVolumeOrigin.xyz));
    cell = ivec3(int(mod(float(cell.x), 128.0)), int(mod(float(cell.y), 48.0)), int(mod(float(cell.z), 128.0)));
    ivec2 texel = ivec2((cell.y % 8) * 128 + cell.x, (cell.y / 8) * 128 + cell.z);
    return texelFetch(LIGHT_VOLUME, texel, 0).rg;
}

#endif
