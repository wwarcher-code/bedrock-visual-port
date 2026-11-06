#ifndef BEDROCKVISUALS_SKY_GLSL
#define BEDROCKVISUALS_SKY_GLSL

// Bedrock's sky radiance (its SkyProbeDeferredShading and SurfaceRadianceUpscalePass shaders; see
// docs/bedrock-renderer-notes.md, "Atmosphere"): Rayleigh scattering of the sun and moon over a zenith-to-horizon
// colour blend, plus a Mie glow around the sun near the horizon. Vibrant Visuals draws each sky pixel with it.
// The inputs are in Bedrock's units (SkyAtmosphere.java).

layout(std140) uniform AtmosphereData {
    vec4 AtmosphereZenith;      // rgb: zenith colour, linear; w: 1 when Bedrock's sky is drawn
    vec4 AtmosphereHorizon;     // rgb: horizon colour, linear; w: this renderer's units per Bedrock unit
    vec4 AtmosphereBlendStops;  // horizon blend stops: min, start, mie start, max
    vec4 AtmosphereScattering;  // Rayleigh strength, sun Mie strength, moon Mie strength, sun glare shape
    vec4 AtmosphereSun;         // rgb: colour, linear; w: illuminance
    vec4 AtmosphereMoon;
    vec4 AtmosphereFog;         // distance fog: start, 1 / (end - start) (fractions of z), z: distance in blocks, w: on
    vec4 VolumeAir;             // volumetric fog air medium: albedo rgb, extinction per block at full density
    vec4 VolumeHeight;          // density = saturate(x × (y relative to camera) + y); z: phase g; w: on
    vec4 SkyProbeParams;        // x: 1 when the sky probe (SkyProbe.java) is there to read
};

#include <bedrockvisuals:octahedral.glsl>
#ifdef SKY_PROBE
uniform sampler2D SKY_PROBE;    // Bedrock's sky probe: the sky, convolved per roughness into 10 mips (SkyProbe)
#endif

// How much of the sky covers terrain at this distance (Bedrock's FogAndDistanceControl): 0 to 1 between the
// air fog's start and end.
float bedrockFogAmount(float distance) {
    if (AtmosphereFog.w < 0.5) {
        return 0.0;
    }
    return clamp((distance / AtmosphereFog.z - AtmosphereFog.x) * AtmosphereFog.y, 0.0, 1.0);
}

// Henyey-Greenstein phase with g = 0.9, as Bedrock writes it: (1 - g^2) / (1 + g^2 - 2gx)^1.5 / 4π folded into
// the constants and the final 1/4π.
float bedrockMiePhase(float x) {
    float d = 1.81 - 1.8 * x;
    return 0.03610002 / (d * sqrt(d));
}

// Radiance along the unit direction d, in Bedrock's units. sunDir and moonDir point towards the sun and moon.
vec3 bedrockSkyRadiance(vec3 d, vec3 sunDir, vec3 moonDir) {
    float stopMin = AtmosphereBlendStops.x;
    float stopStart = AtmosphereBlendStops.y;
    float stopMie = AtmosphereBlendStops.z;
    float stopMax = AtmosphereBlendStops.w;

    float mieFade = 1.0 - smoothstep(0.0, 1.0, clamp((d.y - stopMin + stopMax) / (stopMie - stopMin), 0.0, 1.0));
    mieFade = mieFade * mieFade * mieFade;
    float horizonLow = stopMin - stopMax;
    float horizonWeight = 1.0 - smoothstep(0.0, 1.0, clamp((d.y - horizonLow) / (stopStart - horizonLow), 0.0, 1.0));
    horizonWeight = horizonWeight * horizonWeight * horizonWeight;
    vec3 base = mix(AtmosphereZenith.rgb, AtmosphereHorizon.rgb, horizonWeight);

    float cosSun = dot(d, sunDir);
    float cosMoon = dot(d, moonDir);
    float rayleigh = 0.75 * AtmosphereScattering.x
            * ((1.0 + cosSun * cosSun) * AtmosphereSun.w + (1.0 + cosMoon * cosMoon) * AtmosphereMoon.w);

    float glare = AtmosphereScattering.w;
    // Bedrock computes pow as exp2(log2(x) * glare): away from the sun that's NaN, which saturate turns to 0.
    float xSun = cosSun > 0.0 ? clamp(pow(cosSun, glare), 0.0, 1.0) : 0.0;
    float xMoon = cosMoon > 0.0 ? clamp(pow(cosMoon, glare), 0.0, 1.0) : 0.0;
    vec3 mie = AtmosphereScattering.y * AtmosphereSun.w * xSun * AtmosphereSun.rgb * bedrockMiePhase(xSun)
            + AtmosphereScattering.z * AtmosphereMoon.w * xMoon * AtmosphereMoon.rgb * bedrockMiePhase(xMoon);

    return (base * rayleigh + mieFade * AtmosphereHorizon.rgb * mie) * 0.07957747;
}

// The sky seen along d, in this renderer's units (before exposure), as a reflection of the given roughness
// sees it: Bedrock's pre-convolved sky probe at mip (IBLParameters.y - 1) × (1 - (1 - r)²) of 9
// (DeferredIndirectSpecular with ConvolutionType 1, as captured), here an octahedral map with the same 10 mips
// (SkyProbe.java). Where a shader has no probe bound, or before the first, a ring of 8 directions around d,
// up to 60° out at the roughest mip, stands in for the convolution.
vec3 bedrockSkyReflection(vec3 d, float roughness, vec3 sunDir, vec3 moonDir) {
    float smoothness = clamp(1.0 - roughness, 0.0, 1.0);
    float mip = 1.0 - smoothness * smoothness;
#ifdef SKY_PROBE
    if (SkyProbeParams.x > 0.5) {
        return textureLod(SKY_PROBE, octahedralEncode(d), mip * 9.0).rgb;
    }
#endif
    vec3 sky = bedrockSkyRadiance(d, sunDir, moonDir);
    if (mip > 0.02) {
        vec3 side = normalize(abs(d.y) < 0.9 ? cross(d, vec3(0.0, 1.0, 0.0)) : cross(d, vec3(1.0, 0.0, 0.0)));
        vec3 up = cross(side, d);
        float angle = mip * 1.0471976;
        float c = cos(angle);
        float s = sin(angle);
        vec3 ring = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.78539816;
            ring += bedrockSkyRadiance(normalize(d * c + (side * cos(a) + up * sin(a)) * s), sunDir, moonDir);
        }
        sky = mix(sky, (sky + ring) / 9.0, clamp(mip * 2.0, 0.0, 1.0));
    }
    return sky * AtmosphereHorizon.w;
}

#endif
