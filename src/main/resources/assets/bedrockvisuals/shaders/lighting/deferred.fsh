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
uniform sampler2D CloudShadowMap;  // filled cloud cells (CloudShadows.java)
uniform sampler2D WaterColumnMap; // each column's top water body (WaterColumns.java)
uniform sampler2D CausticsMap;     // Bedrock's renderer/caustics.png: frames of 32x32 stacked vertically
uniform sampler2D PreviousScene;   // the previous frame's HDR scene (BloomPass.java), for reflections

#include <bedrockvisuals:common.glsl>
#include <bedrockvisuals:sky.glsl>


layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
// Pre-exposure luminance, for the auto exposure's histogram (LuminanceMeter.java).
layout(location = 1) out vec4 meterLuminance;
// The HDR colour before exposure, for bloom (BloomPass.java); alpha 0 where Java's own sky is kept.
layout(location = 2) out vec4 hdrColor;


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
    // Bedrock's direct lighting: Lambert (albedo / π) for the diffuse part.
    return ((1.0 - fresnel) * diffuseColor / PI + specular) * radiance * nDotL;
}

// Fraction of a 3x3 texel neighbourhood that sees the light, or -1 outside the map. Depth is reversed in the
// map too: larger values are nearer the light, so a point is lit when nothing nearer the light was stored.
float shadowVisibility(vec3 position, vec3 n, vec3 towardsLight, out float edgeFade) {
    edgeFade = 0.0;
    if (ShadowParams.x < 0.5) {
        return -1.0;
    }
    // Bedrock's normal offset: out along the normal by normal_offset_strength x (1 - N.L) blocks.
    float grazing = clamp(1.0 - dot(n, towardsLight), 0.0, 1.0);
    vec4 clip = ShadowViewProjection * vec4(position + n * ShadowParams.z * grazing, 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return -1.0;
    }
    // Blend towards the sky-light fallback over the outer 5% of the map on each side.
    vec2 edge = min(uv, 1.0 - uv);
    edgeFade = smoothstep(0.0, 0.05, min(edge.x, edge.y));

    const float BIAS = 0.0003; // about 0.15 blocks of the map's depth range
    // One tap: with the lookup snapped to the texel grid, Bedrock's shadow edges are crisp per texel.
    float stored = texture(ShadowMap, uv).r;
    return clip.z >= stored - BIAS ? 1.0 : 0.0;
}

// How much of the light the clouds let through at this point: the point is projected along the light onto the
// middle of the cloud layer; under a filled cell it keeps 1 - cloud_shadow_contribution of the light.
float cloudShadow(vec3 position, vec3 towardsLight) {
    if (CloudShadowField.w <= 0.0 || towardsLight.y <= 0.01) {
        return 1.0;
    }
    float rise = CloudShadowField.z - position.y;
    if (rise <= 0.0) {
        return 1.0; // above the clouds
    }
    vec2 onLayer = position.xz + towardsLight.xz * (rise / towardsLight.y);
    vec2 field = (CloudShadowField.xy + onLayer) / CloudShadowSize.z;
    float filled = texture(CloudShadowMap, (floor(field) + 0.5) / CloudShadowSize.xy).r;
    // Bedrock: a shadowed point keeps at least 1 - contribution of the light.
    return filled > 0.5 ? 1.0 - CloudShadowField.w : 1.0;
}

// Bedrock's volumetric fog (PopulateVolume + VolumeScattering), marched along the view instead of through a
// froxel grid: per step, the medium (water below the surface while the camera is under water, else air with
// its height density), in-scattered sky ambient (1/4π) and sun or moon light through the shadow map and the
// cloud shadows (Henyey-Greenstein), integrated front to back. Out to 250 blocks (volumetric_fog
// far_plane), and faded out where the distance fog takes over, as Bedrock does.
const int VOLUME_STEPS = 48;
const float VOLUME_FAR = 250.0;

// Whether the camera-relative point p is in the top water body of its column (WaterColumns.java). It stands
// in for Bedrock's water front and back depths with the camera above water.
bool inWaterColumn(vec3 p) {
    // The map covers 128 columns each way; past that a texel holds a nearer column.
    if (WaterColumnOrigin.w < 0.5 || max(abs(p.x), abs(p.z)) > 120.0) {
        return false;
    }
    ivec2 texel = ivec2(mod(floor(p.xz + WaterColumnOrigin.xz), 256.0));
    vec4 column = texelFetch(WaterColumnMap, texel, 0) * 255.0;
    float surface = (column.r + column.g * 256.0) / 16.0;
    if (surface <= 0.0) {
        return false;
    }
    float bottom = column.b + column.a * 256.0;
    float y = p.y + WaterColumnOrigin.y;
    return y >= bottom && y < surface;
}

float phaseHG(float g, float cosine) {
    return (1.0 - g * g) * 0.07957747 / pow(max(1.0 + g * (2.0 * cosine + g), 1e-4), 1.5);
}

float volumeShadow(vec3 p, vec3 towardsLight) {
    if (ShadowParams.x < 0.5) {
        return 1.0;
    }
    vec4 clip = ShadowViewProjection * vec4(p, 1.0);
    vec2 uv = clip.xy * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || clip.z <= 0.0 || clip.z >= 1.0) {
        return 1.0;
    }
    return clip.z >= texture(ShadowMap, uv).r - 0.0003 ? 1.0 : 0.0;
}

// rgb: in-scattered light (this renderer's units, before exposure); transmittance in the out parameter.
vec3 volumetricFog(vec3 dir, float distance, out vec3 transmittance) {
    transmittance = vec3(1.0);
    bool water = UnderwaterParams.x > 0.5;
    bool waterBelow = !water && WaterColumnOrigin.w > 0.5;
    if (AtmosphereZenith.w < 0.5 || (VolumeHeight.w < 0.5 && !water && !waterBelow)) {
        return vec3(0.0);
    }
    float end = min(distance, VOLUME_FAR);
    float stepLength = end / float(VOLUME_STEPS);
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    bool moonCasts = ShadowParams.w > 0.5;
    vec3 l = normalize(moonCasts ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moonCasts ? MoonColor.rgb : SunColor.rgb;
    float cosine = dot(l, -dir);
    vec3 ambient = SkyColor.rgb * VolumeCamera.x * 0.07957747;
    vec3 inScattered = vec3(0.0);
    for (int i = 0; i < VOLUME_STEPS; i++) {
        float t = (float(i) + jitter) * stepLength;
        vec3 p = dir * t;
        vec3 extinction;
        vec3 scattering;
        float g;
        if (water && p.y < UnderwaterMedium.w) {
            extinction = UnderwaterMedium.rgb;
            scattering = UnderwaterScattering.rgb;
            g = UnderwaterScattering.w;
        } else if (waterBelow && inWaterColumn(p)) {
            // Seen from above, the water between its surface and the floor scatters too (Bedrock's
            // PopulateVolume picks the water medium between the water's front and back depths). The water
            // pass dims everything behind the surface by the whole path's extinction afterwards, so it isn't
            // applied here as well.
            extinction = vec3(0.0);
            scattering = UnderwaterScattering.rgb;
            g = UnderwaterScattering.w;
        } else {
            float density = clamp(VolumeHeight.x * p.y + VolumeHeight.y, 0.0, 1.0) * VolumeHeight.w;
            extinction = vec3(VolumeAir.w * density);
            scattering = extinction * VolumeAir.rgb;
            g = VolumeHeight.z;
        }
        scattering *= 1.0 - bedrockFogAmount(t);
        float visibility = volumeShadow(p, l) * cloudShadow(p, l);
        vec3 source = scattering * (ambient + light * phaseHG(g, cosine) * visibility);
        vec3 stepTransmittance = exp(-extinction * stepLength);
        inScattered += transmittance * source * (1.0 - stepTransmittance) / max(extinction, vec3(1e-6));
        transmittance *= stepTransmittance;
    }
    return inScattered;
}

// Bedrock's screen-space reflections (ScreenSpaceReflections; reflection_configuration.pc.json: on below
// roughness 0.4, fading from 0.3), for every smooth surface, not only water. The mirror ray is marched through
// the G-buffer's depth; a hit takes the previous frame's HDR colour there. rgb: colour, a: confidence.
const int SSR_STEPS = 48;
vec4 terrainReflection(vec3 origin, vec3 direction) {
    float stepLength = 0.2;
    float travelled = 0.0;
    for (int i = 0; i < SSR_STEPS; i++) {
        travelled += stepLength;
        stepLength *= 1.12;
        vec3 p = origin + direction * travelled;
        vec4 clip = ViewProjection * vec4(p, 1.0);
        if (clip.w <= 0.0) {
            return vec4(0.0);
        }
        vec3 ndc = clip.xyz / clip.w;
        vec2 uv = ndc.xy * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
            return vec4(0.0);
        }
        float sceneDepth = texture(Depth, uv).r;
        if (sceneDepth <= 0.0) {
            continue;
        }
        vec4 scene = InverseViewProjection * vec4(ndc.xy, sceneDepth, 1.0);
        float sceneDistance = length(scene.xyz / scene.w);
        float rayDistance = length(p);
        if (rayDistance > sceneDistance && rayDistance - sceneDistance < max(stepLength * 2.0, 0.5)) {
            // Fade near the screen's edges (fading_distance), where the reflected scene runs out.
            vec2 edge = min(uv, 1.0 - uv);
            float fade = smoothstep(0.0, 0.1, min(edge.x, edge.y));
            return vec4(texture(PreviousScene, uv).rgb, fade);
        }
    }
    return vec4(0.0);
}

// Bedrock's caustics multiplier (DeferredMixedResolutionCaustics), on direct light: on every surface while the
// camera is under water; from above, on what's under water (the point is nudged off its surface into the
// water, so a pool's walls count too). The sheet repeats every 1/scale blocks across world XZ.
float causticsMultiplier(vec3 position, vec3 n) {
    if (UnderwaterParams.y < 0.0) {
        return 1.0;
    }
    if (UnderwaterParams.x < 0.5 && !inWaterColumn(position + n * 0.05)) {
        return 1.0;
    }
    vec2 world = (position.xz + CameraGridOffset.xz) * UnderwaterParams.z;
    vec2 size = vec2(textureSize(CausticsMap, 0));
    float frames = max(floor(size.y / size.x), 1.0);
    vec2 uv = vec2(fract(world.x), (UnderwaterParams.y + fract(world.y)) / frames);
    float pattern = texture(CausticsMap, uv).r * 2.0 * clamp(n.y, 0.0, 1.0);
    float power = UnderwaterParams.w;
    return pow(max(pattern, 1e-6), power) * (power + 1.0);
}

void main() {
    float depth = texture(Depth, texCoord).r;
    if (depth <= 0.0) {
        // No terrain (reversed depth: 0 is the cleared far plane): sky.
        // The view direction from two points along the pixel's ray, not from the camera to the near plane:
        // view bobbing moves the camera by about as much as the near plane's distance, which tipped every
        // sky direction up and down while walking (and the horizon's colours with it).
        vec4 near = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
        vec4 far = InverseViewProjection * vec4(texCoord * 2.0 - 1.0, 0.001, 1.0);
        vec3 ray = normalize(far.xyz / far.w - near.xyz / near.w);
        if (AtmosphereZenith.w > 0.5) {
            // Bedrock's sky, exposed like everything else. Java's sky gradient was skipped and its sun, moon
            // and stars drawn over black (SkyRendererMixin), so the sky adds onto them: alpha 0 under the
            // premultiplied blend.
            vec3 sky = bedrockSkyRadiance(ray, normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
                    * AtmosphereHorizon.w;
            // Volumetric fog in front of the sky, out to its far plane (under water, through the water above).
            vec3 fogTransmittance;
            vec3 fogLight = volumetricFog(ray, VOLUME_FAR, fogTransmittance);
            sky = sky * fogTransmittance + fogLight;
            meterLuminance = vec4(-dot(sky, LUMA), 0.0, 0.0, 1.0);
            hdrColor = vec4(sky, 1.0);
            fragColor = vec4(toDisplay(sky), 0.0);
            return;
        }
        // Java's sky stays (alpha 0 adds nothing), but it's metered, as Bedrock's histogram counts the sky
        // too: its colours, horizon to zenith by the view's elevation, scaled to radiance.
        vec3 skyRadiance = DebugParams.y * mix(SkyHorizonColor.rgb, SkyColor.rgb, clamp(ray.y, 0.0, 1.0));
        // Negative marks sky for the meter's diagnostics; the histogram uses the magnitude (AutoExposure).
        meterLuminance = vec4(-dot(skyRadiance, LUMA), 0.0, 0.0, 1.0);
        hdrColor = vec4(0.0);
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
        hdrColor = vec4(0.0);
        return;
    }
    float sunVisibility = moonCasts ? skyFallback : casterVisibility;
    float moonVisibility = moonCasts ? casterVisibility : skyFallback;
    vec3 snapped = snapToTexel(position, n);
    sunVisibility = min(sunVisibility, cloudShadow(snapped, sunDir));
    moonVisibility = min(moonVisibility, cloudShadow(snapped, moonDir));

    vec3 color = directLight(n, v, sunDir, SunColor.rgb * sunVisibility, diffuseColor, f0, roughness);
    color += directLight(n, v, moonDir, MoonColor.rgb * moonVisibility, diffuseColor, f0, roughness);
    color *= causticsMultiplier(position, n);

    // Bedrock's indirect light (DiscreteIndirectCombinedLightingPass), one colour whatever the normal:
    // max(block light + sky ambient × s × mix(s⁴, s², camera sky light), ambient floor), less where emissive.
    // LightingState holds Bedrock's captured terms.
    float cameraSky = sqrt(VolumeCamera.x) * 15.0 / 16.0;
    float skyCurve = skyLight * mix(pow(skyLight, 4.0), skyLight * skyLight, cameraSky);
    vec3 indirect = max(BlockLightColor.rgb * blockLightColor(blockLight) + SkyColor.rgb * skyCurve, AmbientColor.rgb);
    color += diffuseColor * indirect * (1.0 - emissive);

    // Reflected sky: smooth and metallic surfaces mirror their surroundings. Until there are reflections, the
    // sky in the mirror direction stands in (Bedrock's own sky, else a gradient), fading out with roughness and
    // sky access.
    float nDotV = max(dot(n, v), 1e-4);
    vec3 mirror = reflect(-v, n);
    // The horizon color is the glow towards the sun at sunset; most of the horizon is closer to the zenith's
    // blue, so it gets at most 30% (at full weight, walls facing away from the sun turned pink).
    float horizonShare = 0.3 * (1.0 - clamp(mirror.y * 0.5 + 0.5, 0.0, 1.0));
    vec3 skyInMirror = AtmosphereZenith.w > 0.5 ? bedrockSkyReflection(mirror, roughness, sunDir, moonDir)
            : mix(SkyColor.rgb, SkyHorizonColor.rgb, horizonShare);
    vec3 grazingF0 = max(vec3(1.0 - roughness), f0);
    vec3 skyFresnel = f0 + (grazingF0 - f0) * pow(1.0 - nDotV, 5.0);
    // Bedrock's sky reflection: faded in over sky light 12 to 15 (cubed), at IBLParameters.z 0.5.
    float iblFade = pow(clamp((skyLight * 15.0 - 12.0) / 3.0, 0.0, 1.0), 3.0) * 0.5;
    vec3 reflected = skyInMirror * iblFade;
    float ssrWeight = 1.0 - smoothstep(0.3, 0.4, roughness);
    if (ssrWeight > 0.0) {
        vec4 traced = terrainReflection(position + n * 0.02, mirror);
        reflected = mix(reflected, traced.rgb, traced.a * ssrWeight);
    }
    color += reflected * skyFresnel * (1.0 - 0.7 * roughness);
    color += albedo * emissive * 2.0;

    // Distance fog: like Bedrock, the terrain fades into the sky behind it towards the render distance's edge.
    float fog = AtmosphereZenith.w > 0.5 ? bedrockFogAmount(length(position)) : 0.0;
    if (fog > 0.0) {
        color = mix(color, bedrockSkyRadiance(normalize(position), sunDir, moonDir) * AtmosphereHorizon.w, fog);
    }

    vec3 viewRay = normalize(position);
    vec3 fogTransmittance;
    vec3 fogLight = volumetricFog(viewRay, length(position), fogTransmittance);
    // Under water, the march's water medium is Bedrock's WaterExtinction along the view, up to the surface.
    color = color * fogTransmittance + fogLight;

    meterLuminance = vec4(dot(color, LUMA), 0.0, 0.0, 1.0);
    hdrColor = vec4(color, 1.0);
    fragColor = vec4(toDisplay(color), 1.0);
}
