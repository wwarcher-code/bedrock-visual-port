#ifndef BEDROCKVISUALS_LOCAL_EXPOSURE_GLSL
#define BEDROCKVISUALS_LOCAL_EXPOSURE_GLSL

// Bedrock's local exposure (LocalExposure, BilateralGrid; see docs/bedrock-renderer-notes.md, "Local exposure").
// Luminance is handled as log2 of Bedrock's units, clamped to LuminanceRangeParams (2^-10 to 2^20).

layout(std140) uniform LocalExposureData {
    vec4 LocalExposureParams; // LocalExposureParams x, y, z; w: log2 of the metered average luminance
    vec4 GridDimensions;      // grid cells across, down, luminance slices; w: grid filter radius in cells
    vec4 BlurParams;          // x: blur radius, y: blur step (quarter-resolution pixels); z: unused; w: Bedrock units per renderer unit
    vec4 ScreenParams;        // xy: the screen in pixels; z: 1 when this frame's grid and blur are ready
};

const float LOG_MIN = -10.0;
const float LOG_MAX = 20.0;

float logLuminance(vec3 color) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722)) * BlurParams.w;
    return log2(clamp(luminance, exp2(LOG_MIN), exp2(LOG_MAX)));
}

#ifdef LOCAL_EXPOSURE_APPLY
uniform sampler2D LocalExposureGrid; // the filtered grid: slices side by side
uniform sampler2D LocalExposureBlur; // the blurred log luminance

vec2 localExposureGridAt(vec2 uv, int slice) {
    vec2 grid = GridDimensions.xy;
    vec2 cell = clamp(uv * grid, vec2(0.5), grid - 0.5);
    return texture(LocalExposureGrid, vec2((float(slice) * grid.x + cell.x) / (grid.x * GridDimensions.z),
            cell.y / grid.y)).rg;
}

// Bedrock's ApplyLocalExposure, in log2 stops for a colour (this renderer's units) at screen position uv. With
// L the log luminance, A the log average, D the blur and B the grid's mean at (uv, L):
//   base = grid empty (weight < 0.001) ? D : mix(B, D, z);  change = (A - L) + y (L - base) + x (base - A)
// gridWeight returns the grid's weight there (for the debug view).
float localExposureChange(vec3 color, vec2 uv, out float gridWeight) {
    gridWeight = 0.0;
    if (ScreenParams.z < 0.5) {
        return 0.0;
    }
    float l = logLuminance(color);
    float d = texture(LocalExposureBlur, uv).r;
    float t = (l - LOG_MIN) / (LOG_MAX - LOG_MIN);
    int slices = int(GridDimensions.z);
    float s = t * float(slices) - 0.5;
    int low = clamp(int(floor(s)), 0, slices - 1);
    int high = clamp(int(floor(s)) + 1, 0, slices - 1);
    vec2 g = mix(localExposureGridAt(uv, low), localExposureGridAt(uv, high), clamp(s - floor(s), 0.0, 1.0));
    gridWeight = g.x;
    float base = d;
    if (g.x >= 0.001) {
        base = mix(LOG_MIN + (LOG_MAX - LOG_MIN) * (g.y / g.x), d, LocalExposureParams.z);
    }
    float a = LocalExposureParams.w;
    return (a - l) + LocalExposureParams.y * (l - base) + LocalExposureParams.x * (base - a);
}

// The colour after local exposure, for shaders drawn after the pass (water, clouds): at this fragment.
vec3 locallyExposed(vec3 color) {
    float weight;
    return color * exp2(localExposureChange(color, gl_FragCoord.xy / max(ScreenParams.xy, vec2(1.0)), weight));
}
#endif

#endif
