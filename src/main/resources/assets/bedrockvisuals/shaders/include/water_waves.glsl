// Bedrock's water waves (DoWaterSurfaceBuffer), for packs whose water/*.json turns "waves" on: a sum of sine
// octaves, each turned 1.3993 radians from the last, its frequency and speed scaled and its weight cut by "mix";
// each octave's crest is sharpened by "shape" and drags the next octaves' sample point by "pull". The result is
// the surface's slope, which replaces the flipbook's normal on the water's top faces. Needs WaterData
// (WaveParams0-2, WaveOrigin) declared first.

bool wavesOn() {
    return WaveParams0.x > 0.5;
}

// The wave normal at a camera-relative position.
vec3 waveNormal(vec3 cameraRelative) {
    float freq = WaveParams0.y;
    float speed = WaveParams1.x;
    float shape = WaveParams1.y;
    float time = WaveParams2.z;
    vec2 pos = cameraRelative.xz + WaveOrigin.xz;
    float angle = 0.0;
    float weight = 1.0;
    float sum = 0.0;
    vec2 slope = vec2(0.0);
    int octaves = int(WaveParams0.z + 0.5);
    for (int i = 0; i < 64; i++) {
        if (i >= octaves) {
            break;
        }
        vec2 dir = vec2(sin(angle), cos(angle));
        float phase = dot(dir, pos) * freq + time * 0.5 * speed;
        float s = sin(phase);
        float c = cos(phase);
        float wave = pow((s + 1.0) * 0.5, shape);
        float k = shape * WaveParams0.w * freq * c * weight / (s + 1.0001);
        slope -= k * dir * wave;
        pos -= dir * (c * weight * wave) * WaveParams1.z;
        sum += weight;
        weight *= 1.0 - WaveParams1.w;
        freq *= WaveParams2.x;
        speed *= WaveParams2.y;
        angle += 1.3993;
    }
    slope /= max(sum, 1e-4);
    return normalize(vec3(slope.x, 1.0, slope.y));
}

// On a face with normal geo, the wave normal blended in by how much the face points up (Bedrock's
// normalize(geo + (wave - geo) * geo.y)).
vec3 withWaves(vec3 n, vec3 geo, vec3 cameraRelative) {
    if (!wavesOn() || geo.y <= 0.0) {
        return n;
    }
    return normalize(n + (waveNormal(cameraRelative) - n) * geo.y);
}
