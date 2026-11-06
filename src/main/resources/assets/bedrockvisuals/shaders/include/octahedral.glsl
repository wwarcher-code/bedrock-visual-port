// A direction's place on an octahedral map of the sphere (y up), uv 0 to 1, and back: the sky probe's layout
// (SkyProbe.java), standing in for Bedrock's sky probe cube.

#ifndef BEDROCKVISUALS_OCTAHEDRAL
#define BEDROCKVISUALS_OCTAHEDRAL

vec2 octahedralSigns(vec2 v) {
    return vec2(v.x >= 0.0 ? 1.0 : -1.0, v.y >= 0.0 ? 1.0 : -1.0);
}

vec2 octahedralEncode(vec3 d) {
    d /= abs(d.x) + abs(d.y) + abs(d.z);
    vec2 e = d.xz;
    if (d.y < 0.0) {
        e = (1.0 - abs(d.zx)) * octahedralSigns(d.xz);
    }
    return e * 0.5 + 0.5;
}

vec3 octahedralDecode(vec2 uv) {
    vec2 e = uv * 2.0 - 1.0;
    vec3 d = vec3(e.x, 1.0 - abs(e.x) - abs(e.y), e.y);
    if (d.y < 0.0) {
        d.xz = (1.0 - abs(d.zx)) * octahedralSigns(d.xz);
    }
    return normalize(d);
}

#endif
