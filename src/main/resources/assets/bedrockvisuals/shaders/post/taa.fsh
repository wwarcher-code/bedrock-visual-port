#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's TAAU (the Upscaling material's fragment shader), at the window's resolution (TemporalAA.java;
// docs/bedrock-renderer-notes.md, "Temporal anti-aliasing"):
// - this frame's colour at the render pixel under the display pixel, the jitter taken out;
// - the mean and standard deviation of its 3x3 neighbourhood, and (with dilation) the neighbourhood's longest
//   motion;
// - a box of mean ± gamma × deviation, gamma from the value at rest towards the value in motion by a smoothstep
//   of the motion's length in pixels (capped at one);
// - the history at the reprojected position, read with a 9-tap Catmull-Rom filter, kept to the box: clipped
//   along the line towards this frame's colour (or clamped);
// - blended towards this frame's colour by 0.1 × max(motion factor, clamp(1 - d², 0.05, 1)), d the sample's
//   distance from the pixel centre; all of this frame's colour when there is no history.
// Bedrock reads motion vectors from its G-buffer; here they come from the depth and the camera's movement.

uniform sampler2D FinalColor;  // this frame, as the level left it
uniform sampler2D SceneDepth;  // the level's depth
uniform sampler2D History;     // the last output (linear filtering)

layout(std140) uniform TAAData {
    mat4 InverseViewProjection;     // this frame's, jittered (as the depth was drawn)
    mat4 ViewProjection;            // this frame's, without the jitter
    mat4 PreviousViewProjection;    // the last frame's, without the jitter
    vec4 CameraMotion;              // the camera's movement since the last frame (blocks)
    vec4 SubPixelJitterAndValidHistory;  // xy: jitter (pixels), z: 1 when the history holds earlier frames
    vec4 TAAUpscalingParameters;    // x: gamma in motion, y: gamma at rest, z: clip (else clamp), w: dilate
    vec4 ResolutionRatiosAndFPEpsilon;  // x: render / display, y: display / render, z: epsilon
    vec4 RenderResolution;          // xy: size, zw: its reciprocal
};

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
layout(location = 1) out vec4 historyColor;

// The motion of the surface at a render pixel since the last frame, in pixels (current minus previous).
vec2 motionAt(ivec2 pixel) {
    float depth = texelFetch(SceneDepth, pixel, 0).r;
    vec2 uv = (vec2(pixel) + 0.5) * RenderResolution.zw;
    vec4 world = InverseViewProjection * vec4(uv * 2.0 - 1.0, depth, 1.0);
    world /= world.w;
    vec4 now = ViewProjection * vec4(world.xyz, 1.0);
    vec4 before = PreviousViewProjection * vec4(world.xyz + CameraMotion.xyz, 1.0);
    if (before.w <= 0.0 || now.w <= 0.0) {
        return vec2(0.0);
    }
    return (now.xy / now.w - before.xy / before.w) * 0.5 * RenderResolution.xy;
}

vec3 historyAt(vec2 uv) {
    return textureLod(History, uv, 0.0).rgb;
}

// Catmull-Rom over the history at a position in pixels, from nine bilinear reads (the middle two taps of each
// axis merged into one), as Bedrock reads it.
vec3 historyCatmullRom(vec2 position) {
    vec2 size = RenderResolution.xy;
    vec2 centre = floor(position - 0.5) + 0.5;
    vec2 f = clamp(position - centre, 0.0, 1.0);
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;
    vec2 p0 = (centre - 1.0) / size;
    vec2 p3 = (centre + 2.0) / size;
    vec2 p12 = (centre + offset12) / size;
    vec3 sum = historyAt(vec2(p0.x, p0.y)) * w0.x * w0.y
            + historyAt(vec2(p12.x, p0.y)) * w12.x * w0.y
            + historyAt(vec2(p3.x, p0.y)) * w3.x * w0.y
            + historyAt(vec2(p0.x, p12.y)) * w0.x * w12.y
            + historyAt(vec2(p12.x, p12.y)) * w12.x * w12.y
            + historyAt(vec2(p3.x, p12.y)) * w3.x * w12.y
            + historyAt(vec2(p0.x, p3.y)) * w0.x * w3.y
            + historyAt(vec2(p12.x, p3.y)) * w12.x * w3.y
            + historyAt(vec2(p3.x, p3.y)) * w3.x * w3.y;
    return max(sum, vec3(0.0));
}

// The history moved onto the box: along the line from it towards this frame's colour, to where the line enters
// the box; clamped when the line misses it.
vec3 clipToBox(vec3 history, vec3 current, vec3 low, vec3 high) {
    // Inside the box the history is kept as it is (Bedrock tests this first).
    if (all(greaterThanEqual(history, low)) && all(lessThanEqual(history, high))) {
        return history;
    }
    vec3 delta = current - history;
    float len = length(delta);
    vec3 dir = len > 0.0 ? delta / len : delta;
    float tNear = -65000.0;
    float tFar = 65000.0;
    bool miss = false;
    for (int c = 0; c < 3; c++) {
        if (abs(dir[c]) > ResolutionRatiosAndFPEpsilon.z) {
            float t0 = (low[c] - history[c]) / dir[c];
            float t1 = (high[c] - history[c]) / dir[c];
            tNear = max(tNear, min(t0, t1));
            tFar = min(tFar, max(t0, t1));
            if (tNear > tFar || tFar < 0.0) {
                miss = true;
            }
        } else if (history[c] < low[c] || history[c] > high[c]) {
            miss = true;
        }
    }
    if (miss) {
        return clamp(history, low, high);
    }
    return history + dir * (tNear > 0.0 ? tNear : tFar);
}

void main() {
    vec2 display = floor(texCoord * RenderResolution.xy) + 0.5;
    vec2 render = display * ResolutionRatiosAndFPEpsilon.x - SubPixelJitterAndValidHistory.xy - 0.5;
    ivec2 pixel = clamp(ivec2(round(render)), ivec2(0), ivec2(RenderResolution.xy) - 1);
    vec3 current = texelFetch(FinalColor, pixel, 0).rgb;
    vec2 motion = motionAt(pixel);
    vec3 sum = vec3(0.0);
    vec3 sumSquares = vec3(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 p = clamp(pixel + ivec2(x, y), ivec2(0), ivec2(RenderResolution.xy) - 1);
            vec3 c = texelFetch(FinalColor, p, 0).rgb;
            sum += c;
            sumSquares += c * c;
            if (TAAUpscalingParameters.w != 0.0 && (x != 0 || y != 0)) {
                vec2 m = motionAt(p);
                if (dot(m, m) > dot(motion, motion)) {
                    motion = m;
                }
            }
        }
    }
    vec3 mean = sum / 9.0;
    vec3 deviation = sqrt(max(sumSquares / 9.0 - mean * mean, 0.0));
    float speed = clamp(length(motion), 0.0, 1.0);
    float moving = speed * speed * (3.0 - 2.0 * speed);
    float gamma = mix(TAAUpscalingParameters.y, TAAUpscalingParameters.x, moving);
    vec3 low = mean - gamma * deviation;
    vec3 high = mean + gamma * deviation;

    vec2 previous = clamp(display - motion * ResolutionRatiosAndFPEpsilon.y, vec2(0.0), RenderResolution.xy - 1.0);
    vec3 history = historyCatmullRom(previous);
    history = TAAUpscalingParameters.z != 0.0 ? clipToBox(history, current, low, high) : clamp(history, low, high);

    vec2 offset = render - vec2(pixel);
    float near = clamp(1.0 - ResolutionRatiosAndFPEpsilon.y * dot(offset, offset), 0.05, 1.0);
    float blend = SubPixelJitterAndValidHistory.z != 0.0 ? max(moving, near) * 0.1 : 1.0;
    vec3 result = mix(history, current, blend);
    historyColor = vec4(result, 0.0);
    // Debug (SubPixelJitterAndValidHistory.w): 1 this frame's sample alone, 2 the motion (pixels × 0.25 + 0.5
    // in red and green), 3 the history read, unclipped.
    int debugMode = int(SubPixelJitterAndValidHistory.w + 0.5);
    if (debugMode == 1) {
        result = current;
    } else if (debugMode == 2) {
        result = vec3(clamp(motion * 0.25 + 0.5, 0.0, 1.0), 0.0);
    } else if (debugMode == 3) {
        result = historyCatmullRom(previous);
    }
    fragColor = vec4(result, 1.0);
}
