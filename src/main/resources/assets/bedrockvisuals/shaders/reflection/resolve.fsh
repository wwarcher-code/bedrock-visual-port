#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's SSRGetReflectedColor (ReflectionPass.java): each pixel's reflection is the HDR scene (this renderer's
// units, before exposure) where its ray hit, with the hit's confidence in alpha. With temporal accumulation on
// (SSRTemporalAccumulationParams (1, 0.5, 2, 0.03125) in GPU captures: on, the clamp's scale, the
// neighbourhood's radius, the new frame's weight), last frame's reflection, at where this pixel's reflecting
// point was then, is clamped to the neighbourhood's mean ± 0.5 standard deviations (5 × 5 hits, misses counted
// as nothing) and moved towards this frame's by 1/32: steady reflections that don't smear as things move.
// Pixels with no water stay marked (alpha -2).

uniform sampler2D March;   // the filled march: hit xy, the reflecting point's distance along the view, confidence
uniform sampler2D Scene;   // the lighting pass's HDR scene this frame
uniform sampler2D History; // last frame's resolve

#include <bedrockvisuals:common.glsl>

layout(std140) uniform ReflectionData {
    vec4 TemporalParams; // SSRTemporalAccumulationParams
    vec4 HistoryParams;  // x: 1 when the history holds last frame's resolve
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 resolveOut;

// The reflection at a march's hit, premultiplied by its confidence (a miss is nothing), so misses and hits
// average and accumulate as they should and the water lays it over its sky reflection directly.
vec4 reflectedAt(vec4 march) {
    return march.w < 0.0 ? vec4(0.0) : vec4(texture(Scene, march.xy).rgb * march.w, march.w);
}

vec3 viewRay(vec2 uv) {
    vec4 near = InverseViewProjection * vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec4 far = InverseViewProjection * vec4(uv * 2.0 - 1.0, 0.001, 1.0);
    return normalize(far.xyz / far.w - near.xyz / near.w);
}

void main() {
    vec4 march = texture(March, texCoord);
    if (march.w < -1.5) {
        resolveOut = vec4(0.0, 0.0, 0.0, -2.0);
        return;
    }
    vec4 current = reflectedAt(march);
    if (TemporalParams.x <= 0.0 || HistoryParams.x < 0.5) {
        resolveOut = current;
        return;
    }
    // Where this pixel's reflecting point was on last frame's screen.
    vec3 point = viewRay(texCoord) * march.z;
    vec4 previousClip = PreviousViewProjection * vec4(point + CameraMotion.xyz, 1.0);
    vec2 previousUv = previousClip.xy / max(previousClip.w, 1e-4) * 0.5 + 0.5;
    if (previousClip.w <= 0.0 || any(lessThan(previousUv, vec2(0.0))) || any(greaterThan(previousUv, vec2(1.0)))) {
        resolveOut = current;
        return;
    }
    // The neighbourhood's mean and variance (Welford's running form, as Bedrock's loop keeps it).
    int radius = int(TemporalParams.z + 0.5);
    vec2 texel = 1.0 / vec2(textureSize(March, 0));
    vec4 mean = vec4(0.0);
    vec4 m2 = vec4(0.0);
    float count = 0.0;
    for (int y = -radius; y <= radius; y++) {
        for (int x = -radius; x <= radius; x++) {
            vec4 neighbour = reflectedAt(texture(March, texCoord + vec2(float(x), float(y)) * texel));
            count += 1.0;
            vec4 delta = neighbour - mean;
            mean += delta / count;
            m2 += (neighbour - mean) * delta;
        }
    }
    vec4 deviation = sqrt(max(m2 / count, vec4(0.0))) * TemporalParams.y;
    vec4 history = texture(History, previousUv);
    if (history.w < -1.5) {
        history = current; // last frame saw no water there
    }
    history = clamp(history, mean - deviation, mean + deviation);
    resolveOut = mix(history, current, TemporalParams.w);
}
