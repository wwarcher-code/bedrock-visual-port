#ifndef BEDROCKVISUALS_LOCAL_EXPOSURE_GLSL
#define BEDROCKVISUALS_LOCAL_EXPOSURE_GLSL

// Bedrock's local exposure (LocalExposure, BilateralGrid; see docs/bedrock-renderer-notes.md, "Local exposure").
// Luminance is handled as log2 of Bedrock's units, clamped to LuminanceRangeParams (2^-10 to 2^20).

layout(std140) uniform LocalExposureData {
    vec4 LocalExposureParams; // LocalExposureParams x, y, z; w: log2 of the metered average luminance
    vec4 GridDimensions;      // grid cells across, down, luminance slices; w: grid filter radius in cells
    vec4 BlurParams;          // x: blur radius, y: blur step (scene pixels); z: unused; w: Bedrock units per renderer unit
};

const float LOG_MIN = -10.0;
const float LOG_MAX = 20.0;

float logLuminance(vec3 color) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722)) * BlurParams.w;
    return log2(clamp(luminance, exp2(LOG_MIN), exp2(LOG_MAX)));
}

#endif
