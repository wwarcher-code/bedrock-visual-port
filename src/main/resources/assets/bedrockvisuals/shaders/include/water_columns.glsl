// Bedrock's water front and back depths, as this renderer stands in for them with the camera above water: a
// map of each column's top water body around the camera (WaterColumns.java). Included by the lighting pass
// and the water pass, which must agree on what lies under the water: the lighting pass dims what's under it
// by the water above each point, and the water pass dims only what the lighting pass didn't.
// Needs WATER_COLUMNS, the map's sampler, and LightingData (common.glsl).

#ifndef BEDROCKVISUALS_WATER_COLUMNS
#define BEDROCKVISUALS_WATER_COLUMNS

// The map covers 128 columns each way; past this a texel can hold a nearer column.
const float WATER_COLUMN_RANGE = 120.0;

// How far below the water's surface the camera-relative point p is (blocks), or -1 when it isn't in the top
// water body of its column (WaterColumns.java). It stands in for Bedrock's water front and back depths with
// the camera above water.
float depthBelowWaterSurface(vec3 p) {
    // The map covers 128 columns each way; past that a texel holds a nearer column.
    if (WaterColumnOrigin.w < 0.5 || max(abs(p.x), abs(p.z)) > WATER_COLUMN_RANGE) {
        return -1.0;
    }
    ivec2 texel = ivec2(mod(floor(p.xz + WaterColumnOrigin.xz), 256.0));
    vec4 column = texelFetch(WATER_COLUMNS, texel, 0) * 255.0;
    float surface = (column.r + column.g * 256.0) / 16.0;
    if (surface <= 0.0) {
        return -1.0;
    }
    float bottom = column.b + column.a * 256.0;
    float y = p.y + WaterColumnOrigin.y;
    // Positions come from the view (with view bobbing's sway, up to about 0.1 blocks) and the height from the
    // camera without it, so floors lying exactly on the body's bottom get some slack: without it they flickered
    // in and out of the water while walking.
    return y >= bottom - 0.3 && y < surface ? max(surface - y, 0.0) : -1.0;
}

// The point a visible surface's water test is made at: nudged back towards the camera, into the water in
// front of it (its face lies exactly on a block boundary, where the column or the body's bottom could go either
// way). Both passes use it, so they agree.
vec3 waterTestPoint(vec3 p) {
    return p - normalize(p) * 0.05;
}

// The water's extinction between its surface and the surface at p, along the view from the camera (above
// water), or 1 when p isn't under the mapped water.
vec3 waterTransmittanceTo(vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(waterTestPoint(p));
    if (depth < 0.0) {
        return vec3(1.0);
    }
    float path = min(depth / max(-dir.y, 0.05), 64.0);
    return exp(-UnderwaterMedium.rgb * path);
}

// The light the water scatters towards the camera over `path` blocks of it below the surface, along the view
// direction dir: what the lighting pass's march adds within the map (sky ambient over 4π and the sun or moon
// through the water's phase function, dimmed by the water in front of each point along the view), in closed
// form for a uniform layer, for water the map doesn't cover (farther out, and Distant Horizons' water). The
// sun's share is scaled by sunVisibility (whether what's beneath is in shade). This renderer's units, before exposure.
vec3 waterInScatter(vec3 dir, float path, float sunVisibility) {
    bool moon = ShadowParams.w > 0.5;
    vec3 l = normalize(moon ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moon ? MoonColor.rgb : SunColor.rgb;
    float g = UnderwaterScattering.w;
    float cosine = dot(l, -dir);
    float phase = (1.0 - g * g) * 0.07957747 / pow(max(1.0 + g * (2.0 * cosine + g), 1e-4), 1.5);
    vec3 ambient = SkyColor.rgb * VolumeCamera.x * 0.07957747;
    vec3 sigma = max(UnderwaterMedium.rgb, vec3(1e-5));
    return UnderwaterScattering.rgb * (ambient + light * phase * sunVisibility) * (1.0 - exp(-sigma * path)) / sigma;
}

#endif
