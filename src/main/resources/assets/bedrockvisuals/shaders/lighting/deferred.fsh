#version 330
#extension GL_ARB_separate_shader_objects : require

// Deferred lighting: turns the G-buffer into lit color. Terrain only. Within the shadow map's range the sun
// (or moon) is tested against it; beyond it, sky light stands in for sun visibility.

uniform sampler2D Albedo;
uniform sampler2D Normal;
uniform sampler2D Material;
uniform sampler2D Light;
uniform sampler2D Depth;
uniform sampler2D ShadowMap;

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>

// Share of light reaching the ground that bounces back onto faces turned towards it: a neutral ground albedo
// of 0.12 times a bounce strength, fitted with LightingState's factors to Bedrock screenshots.
const float GROUND_BOUNCE = 0.238;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
// Pre-exposure luminance, for the auto exposure's histogram (LuminanceMeter.java).
layout(location = 1) out vec4 meterLuminance;


vec3 directLight(vec3 n, vec3 v, vec3 l, vec3 radiance, vec3 diffuseColor, vec3 f0, float roughness) {
    float nDotL = max(dot(n, l), 0.0);
    if (nDotL <= 0.0) {
        return vec3(0.0);
    }
    vec3 h = normalize(l + v);
    float nDotV = max(dot(n, v), 1e-4);
    vec3 fresnel = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
    float geometry = smithG1(nDotV, roughness) * smithG1(nDotL, roughness);
    vec3 specular = fresnel * ggx(max(dot(n, h), 0.0), roughness) * geometry / (4.0 * nDotV * nDotL);
    return ((1.0 - fresnel) * diffuseColor + specular) * radiance * nDotL;
}

// Fraction of a 3x3 texel neighbourhood that sees the light, or -1 outside the map. Depth is reversed in the
// map too: larger values are nearer the light, so a point is lit when nothing nearer the light was stored.
float shadowVisibility(vec3 position, vec3 n, vec3 towardsLight, out float edgeFade) {
    edgeFade = 0.0;
    if (ShadowParams.x < 0.5) {
        return -1.0;
    }
    // Push the lookup out along the normal, more on faces that graze the light, against self-shadowing.
    float grazing = 1.0 - max(dot(n, towardsLight), 0.0);
    vec4 clip = ShadowViewProjection * vec4(position + n * ShadowParams.z * (1.0 + 2.0 * grazing), 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return -1.0;
    }
    // Blend towards the sky-light fallback over the outer 5% of the map on each side.
    vec2 edge = min(uv, 1.0 - uv);
    edgeFade = smoothstep(0.0, 0.05, min(edge.x, edge.y));

    const float BIAS = 0.0003; // about 0.15 blocks of the map's depth range
    float lit = 0.0;
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            float stored = texture(ShadowMap, uv + vec2(x, y) * ShadowParams.y).r;
            lit += clip.z >= stored - BIAS ? 1.0 : 0.0;
        }
    }
    return lit / 9.0;
}

void main() {
    float depth = texture(Depth, texCoord).r;
    if (depth <= 0.0) {
        // No terrain (reversed depth: 0 is the cleared far plane): sky.
        vec4 near = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
        vec3 ray = normalize(near.xyz / near.w);
        if (AtmosphereZenith.w > 0.5) {
            // Bedrock's sky, exposed like everything else. Java's sky gradient was skipped and its sun, moon
            // and stars drawn over black (SkyRendererMixin), so the sky adds onto them: alpha 0 under the
            // premultiplied blend.
            vec3 sky = bedrockSkyRadiance(ray, normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
                    * AtmosphereHorizon.w;
            meterLuminance = vec4(-dot(sky, LUMA), 0.0, 0.0, 1.0);
            fragColor = vec4(toDisplay(sky), 0.0);
            return;
        }
        // Java's sky stays (alpha 0 adds nothing), but it's metered, as Bedrock's histogram counts the sky
        // too: its colours, horizon to zenith by the view's elevation, scaled to radiance.
        vec3 skyRadiance = DebugParams.y * mix(SkyHorizonColor.rgb, SkyColor.rgb, clamp(ray.y, 0.0, 1.0));
        // Negative marks sky for the meter's diagnostics; the histogram uses the magnitude (AutoExposure).
        meterLuminance = vec4(-dot(skyRadiance, LUMA), 0.0, 0.0, 1.0);
        fragColor = vec4(0.0);
        return;
    }

    vec3 albedo = toLinear(texture(Albedo, texCoord).rgb);
    vec3 n = normalize(texture(Normal, texCoord).rgb * 2.0 - 1.0);
    vec4 mers = texture(Material, texCoord);
    vec2 levels = texture(Light, texCoord).rg;

    float metalness = mers.r;
    float emissive = mers.g;
    float roughness = clamp(mers.b, 0.05, 1.0);

    vec4 world = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
    vec3 v = normalize(-world.xyz / world.w); // the camera is at the origin

    // Minecraft's light coordinates are (level * 16 + 8) / 256; map back to 0..1.
    vec2 light = clamp((levels * 256.0 - 8.0) / 240.0, 0.0, 1.0);
    float blockLight = light.x;
    float skyLight = light.y;

    vec3 diffuseColor = albedo * (1.0 - metalness);
    vec3 f0 = mix(vec3(0.04), albedo, metalness);
    // Sky light alone: the stand-in beyond the shadow map, and a guard inside it, since Sodium only hands the
    // shadow pass the chunks the camera sees, so a cave's roof may be missing from the map.
    float skyFallback = pow(skyLight, 4.0);
    // Bedrock's sky light heuristic: no sun or moon at all where there's no sky light.
    float skyGuard = skyLight > 0.0 ? 1.0 : 0.0;

    vec3 position = world.xyz / world.w;
    vec3 sunDir = normalize(SunDirection.xyz);
    vec3 moonDir = normalize(MoonDirection.xyz);
    bool moonCasts = ShadowParams.w > 0.5;
    float edgeFade;
    float mapped = shadowVisibility(snapToTexel(position, n), n, moonCasts ? moonDir : sunDir, edgeFade);
    float casterVisibility = mapped < 0.0 ? skyFallback : mix(skyFallback, mapped * skyGuard, edgeFade);
    if (DebugParams.x > 0.5) {
        fragColor = vec4(mapped < 0.0 ? vec3(1.0, 0.0, 0.0) : vec3(mapped), 1.0);
        meterLuminance = vec4(0.18, 0.0, 0.0, 1.0);
        return;
    }
    float sunVisibility = moonCasts ? skyFallback : casterVisibility;
    float moonVisibility = moonCasts ? casterVisibility : skyFallback;

    vec3 color = directLight(n, v, sunDir, SunColor.rgb * sunVisibility, diffuseColor, f0, roughness);
    color += directLight(n, v, moonDir, MoonColor.rgb * moonVisibility, diffuseColor, f0, roughness);

    // Sky fill from the hemisphere a face sees: upward faces see all of the sky, walls half of it and the
    // ground below, which bounces back some sun, moon and sky. The fill takes the zenith's color even on walls:
    // in Bedrock, shaded walls are blue, not the paler horizon haze (the fit settled on all zenith).
    float upness = n.y * 0.5 + 0.5;
    vec3 skySeen = SkyColor.rgb * upness;
    // The ground in front of a shaded wall is usually sunlit, so the bounce ignores this pixel's own shadow.
    vec3 groundLight = (SunColor.rgb * max(sunDir.y, 0.0) + MoonColor.rgb * max(moonDir.y, 0.0)) * skyFallback
            + SkyColor.rgb;
    vec3 bounce = GROUND_BOUNCE * groundLight * (1.0 - upness);
    color += diffuseColor * (skySeen + bounce) * skyLight * skyLight;

    // Reflected sky: smooth and metallic surfaces mirror their surroundings. Until there are reflections, the
    // sky gradient in the mirror direction stands in, fading out with roughness and sky access.
    float nDotV = max(dot(n, v), 1e-4);
    vec3 mirror = reflect(-v, n);
    // The horizon color is the glow towards the sun at sunset; most of the horizon is closer to the zenith's
    // blue, so it gets at most 30% (at full weight, walls facing away from the sun turned pink).
    float horizonShare = 0.3 * (1.0 - clamp(mirror.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 skyInMirror = mix(SkyColor.rgb, SkyHorizonColor.rgb, horizonShare);
    vec3 grazingF0 = max(vec3(1.0 - roughness), f0);
    vec3 skyFresnel = f0 + (grazingF0 - f0) * pow(1.0 - nDotV, 5.0);
    color += skyInMirror * skyFresnel * (1.0 - 0.7 * roughness) * skyLight * skyLight;
    color += diffuseColor * AmbientColor.rgb;
    // BlockLightColor carries its exposure (LightingState.blockLightStrength): faint by day, strong at night.
    color += diffuseColor * BlockLightColor.rgb * blockLightColor(blockLight);
    color += albedo * emissive * 2.0;

    meterLuminance = vec4(dot(color, LUMA), 0.0, 0.0, 1.0);
    fragColor = vec4(toDisplay(color), 1.0);
}
