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
};

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

#endif
