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
    return y >= bottom && y < surface ? surface - y : -1.0;
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

#endif
