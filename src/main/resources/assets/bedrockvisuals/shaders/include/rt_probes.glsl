#ifndef BEDROCKVISUALS_RT_PROBES_GLSL
#define BEDROCKVISUALS_RT_PROBES_GLSL

// The ray-traced light around the camera (raytrace/EntityProbes, render/LightingPass's RtEntityData): probes of
// the light from emissive texels, one a block, as ambient cubes (the emitted light's at face 0, the bounced
// light's at 6), and the lights entities carry. For entities (entity.fsh), and for the light the air and water
// scatter (lighting/volume.fsh, the lighting pass's water haze).
uniform sampler2D RtProbeMap;
layout(std140) uniform RtEntityData {
    vec4 RtProbeCorner;  // xyz: the probe grid's lowest corner relative to the camera; w: 1 when the emitted
                         // light is on this frame
    vec4 RtEntityParams; // x: the share of Bedrock's block light kept under it; y: lights carried; z: the gain;
                         // w: 1 when the bounced light is on this frame, 2 when it brings the sky's light back
    vec4 RtCarried[64];  // per light: position relative to the camera and radius; colour x strength, reach
};
const ivec3 RT_PROBES = ivec3(48, 24, 48); // EntityProbes.X, Y, Z
const int RT_PROBE_TEXELS = 13;             // a probe's texels: twelve faces, then the light from every direction

// One probe's light on a surface facing n (its ambient cube at face 0 or 6), or alpha 0 for a probe inside a
// solid block.
vec4 rtProbe(ivec3 cell, vec3 n, int face) {
    ivec2 at = ivec2(cell.x * RT_PROBE_TEXELS + face, cell.z + cell.y * RT_PROBES.z);
    vec4 x = texelFetch(RtProbeMap, at + ivec2(n.x >= 0.0 ? 0 : 1, 0), 0);
    vec4 y = texelFetch(RtProbeMap, at + ivec2(n.y >= 0.0 ? 2 : 3, 0), 0);
    vec4 z = texelFetch(RtProbeMap, at + ivec2(n.z >= 0.0 ? 4 : 5, 0), 0);
    return vec4(x.rgb * n.x * n.x + y.rgb * n.y * n.y + z.rgb * n.z * n.z, x.a);
}

// The probes' light at the camera-relative point p on a surface facing n (face 0: emitted, 6: bounced): the
// eight probes around it, blended by distance, leaving out those in solid blocks. Negative outside the grid.
vec3 rtProbes(vec3 p, vec3 n, int face) {
    vec3 q = p + n * 0.3 - RtProbeCorner.xyz - 0.5; // probe centres at whole numbers
    ivec3 base = ivec3(floor(q));
    if (any(lessThan(base, ivec3(0))) || any(greaterThanEqual(base + 1, RT_PROBES))) {
        return vec3(-1.0);
    }
    vec3 f = q - vec3(base);
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 corner = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        vec3 w3 = mix(1.0 - f, f, vec3(corner));
        float w = w3.x * w3.y * w3.z;
        vec4 probe = rtProbe(base + corner, n, face);
        sum += probe.rgb * w * probe.a;
        weight += w * probe.a;
    }
    return weight > 1e-3 ? sum / weight : vec3(0.0);
}

// The probes' light at the camera-relative point p from every direction (the mean of the six faces: emitted,
// and bounced when on), for light the air or water scatters. Negative when the traced light is off this frame
// or p is outside the grid.
vec3 rtProbesAverage(vec3 p) {
    if (RtProbeCorner.w < 0.5) {
        return vec3(-1.0);
    }
    vec3 q = p - RtProbeCorner.xyz - 0.5;
    ivec3 base = ivec3(floor(q));
    if (any(lessThan(base, ivec3(0))) || any(greaterThanEqual(base + 1, RT_PROBES))) {
        return vec3(-1.0);
    }
    // Each probe's thirteenth texel holds it (EntityProbes): one read a probe.
    vec3 f = q - vec3(base);
    vec3 sum = vec3(0.0);
    float weight = 0.0;
    for (int i = 0; i < 8; i++) {
        ivec3 corner = ivec3(i & 1, (i >> 1) & 1, (i >> 2) & 1);
        ivec3 cell = base + corner;
        vec4 around = texelFetch(RtProbeMap, ivec2(cell.x * RT_PROBE_TEXELS + 12, cell.z + cell.y * RT_PROBES.z), 0);
        vec3 w3 = mix(1.0 - f, f, vec3(corner));
        float w = w3.x * w3.y * w3.z * around.a;
        sum += around.rgb * w;
        weight += w;
    }
    return weight > 1e-3 ? sum / weight : vec3(0.0);
}

// The block light term for scattered light (BlockLightColor x Bedrock's falloff at the level): the traced light
// in its place, or over the share of it kept (as on the surfaces), where the traced light is on. Held to twice
// Bedrock's term: the traced light right by a lava pool is many times it, and with the camera just above one
// the haze tinted the whole screen, the far sky too. Its shadows and colour still show (walls, stained glass).
vec3 rtScatteredBlockLight(vec3 p, vec3 blockTerm) {
    vec3 traced = rtProbesAverage(p);
    return traced.x >= 0.0 ? max(min(traced, blockTerm * 2.0), blockTerm * RtEntityParams.x) : blockTerm;
}

#endif
