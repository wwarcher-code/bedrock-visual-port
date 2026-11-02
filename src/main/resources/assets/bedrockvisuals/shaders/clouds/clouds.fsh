#version 330
#extension GL_ARB_separate_shader_objects : require

// Bedrock's cloud lighting (CloudsForwardPBR, see docs/bedrock-renderer-notes.md, "Clouds"), on Java's cloud
// mesh. Each cloud cell is lit by:
// - the sky's ambient light, times the cloud colour;
// - the sun (or moon) scattered through the cloud: a Henyey-Greenstein phase, faded by how thick the cloud is
//   along the view and by the light's path through it (Beer-Lambert);
// - wrapped diffuse from the sun on the face, plus light through the back of thin parts;
// - a little reflected sky;
// then blended towards the sky behind it with distance, and exposed and tone mapped like everything else.
// Bedrock measures those paths through neighbouring cells too; here they stay inside the one cell.

#include <minecraft:fog.glsl>
#include <minecraft:oit.glsl>
#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>

layout(std140) uniform CloudInfo {
    vec4 CloudColor;
    vec3 CloudOffset;
    vec3 CellSize;
};

layout(location = 0) in vec3 cloudPosition;
layout(location = 1) in vec3 cellPosition;
layout(location = 2) flat in vec3 cloudNormal;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

// Bedrock's CloudLightingUniforms and diffuse wrap, from GPU captures (the same at every time of day):
// x: thickness (blocks) over which the scattered and back light fade, y: Henyey-Greenstein g, z: reflected sky,
// w: extinction per block along the light's path.
const vec4 CLOUD_LIGHTING = vec4(16.0, 0.5, 5.0, 0.0625);
const float DIFFUSE_WRAP = 0.307;
// CloudColor.a in the captures.
const float CLOUD_OPACITY = 0.9;
// Bedrock caps the paths at 16 blocks.
const float MAX_PATH = 16.0;

// Distance from p (inside the cell) to where the ray along d leaves it.
float exitDistance(vec3 p, vec3 d) {
    vec3 size = max(CellSize, vec3(1e-3));
    vec3 far = mix(-p, size - p, step(0.0, d));
    vec3 t = vec3(abs(d.x) > 1e-5 ? far.x / d.x : MAX_PATH,
             abs(d.y) > 1e-5 ? far.y / d.y : MAX_PATH,
             abs(d.z) > 1e-5 ? far.z / d.z : MAX_PATH);
    return clamp(min(t.x, min(t.y, t.z)), 0.0, MAX_PATH);
}

float cloudAlpha();

vec4 cloudColor() {
    vec3 view = normalize(cloudPosition);
    vec3 n = cloudNormal;
    bool moonLights = ShadowParams.w > 0.5;
    vec3 l = normalize(moonLights ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moonLights ? MoonColor.rgb : SunColor.rgb;
    // Bedrock's cloud colour: white by day, (0.1, 0.1, 0.15) at midnight in the captures, greyed by rain and
    // thunder (Weather.java). Java's own CloudColor isn't used.
    vec3 albedo = VolumeCamera.yzw;

    // How far the view travels inside the cell from this face, and the light's path from the middle of that.
    float viewPath = exitDistance(clamp(cellPosition, vec3(0.0), CellSize), view);
    vec3 middle = clamp(cellPosition + view * viewPath * 0.5, vec3(0.0), CellSize);
    float lightPath = exitDistance(middle, l);

    // Sky ambient (Bedrock's SkyAmbientLightColorIntensity is this renderer's sky fill).
    vec3 color = SkyColor.rgb * albedo;

    // Scattered sunlight. Bedrock's phase takes the light direction against the direction to the camera.
    float g = CLOUD_LIGHTING.y;
    float cosine = dot(l, -view);
    float phase = (1.0 - g * g) * 0.07957747 / pow(1.0 + g * (2.0 * cosine + g), 1.5);
    float thin = 1.0 - smoothstep(0.0, 1.0, clamp(viewPath * 0.5 / CLOUD_LIGHTING.x, 0.0, 1.0));
    float transmitted = exp(-lightPath * CLOUD_LIGHTING.w);
    color += phase * thin * transmitted * light;

    // Wrapped diffuse, and light through the back where the cloud is thin.
    float wrap = DIFFUSE_WRAP;
    float front = max((dot(n, l) + wrap) / ((1.0 + wrap) * (1.0 + wrap)), 0.0);
    float back = max((dot(-n, l) + wrap) / ((1.0 + wrap) * (1.0 + wrap)), 0.0)
            * (1.0 - smoothstep(0.0, 1.0, clamp(viewPath / CLOUD_LIGHTING.x, 0.0, 1.0)));
    color += (front + back) / PI * albedo * light;

    // Reflected sky, at Bedrock's dielectric F0.
    if (AtmosphereZenith.w > 0.5) {
        float nDotV = max(dot(n, -view), 0.0);
        float fresnel = 0.04 + 0.96 * pow(1.0 - nDotV, 5.0);
        color += CLOUD_LIGHTING.z * fresnel * bedrockSkyReflection(reflect(view, n), 1.0, normalize(SunDirection.xyz),
                normalize(MoonDirection.xyz));
    }

    // Towards the sky behind with distance: Bedrock's saturate(distance / DistanceControl.x - 0.9), where
    // DistanceControl.x is the cloud range (3 × the render distance; 1152 in the captures). Here Java's range.
    float range = max(FogCloudsEnd, 1.0);
    float distance = length(cloudPosition);
    if (AtmosphereZenith.w > 0.5) {
        float fog = clamp(distance / range - 0.9, 0.0, 1.0);
        color = mix(color, bedrockSkyRadiance(view, normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
                * AtmosphereHorizon.w, fog);
    }
    return vec4(toDisplay(color), cloudAlpha());
}

// Bedrock's clouds keep their opacity and fade into the sky instead; Java's mesh ends at its cloud range, so
// the last 10% fades out.
float cloudAlpha() {
    return CLOUD_OPACITY * (1.0 - smoothstep(0.9, 1.0, length(cloudPosition) / max(FogCloudsEnd, 1.0)));
}

void main() {
    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, cloudAlpha());
    #else
    vec4 color = cloudColor();
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif
    fragColor = color;
    #endif
}
