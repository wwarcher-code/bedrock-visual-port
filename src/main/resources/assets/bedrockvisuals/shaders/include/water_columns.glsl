// Bedrock's water front and back depths, as this renderer stands in for them with the camera above water: a
// map of each column's top water body around the camera (WaterColumns.java). Included by the lighting pass
// and the water pass, which must agree on what lies under the water: the lighting pass dims what's under it
// by the water above each point, and the water pass dims only what the lighting pass didn't.
// Needs WATER_COLUMNS, the map's sampler, and LightingData (common.glsl).

#ifndef BEDROCKVISUALS_WATER_COLUMNS
#define BEDROCKVISUALS_WATER_COLUMNS

// The map covers 256 columns each way; past this a texel can hold a nearer column.
const float WATER_COLUMN_RANGE = 248.0;

// One column of the map (WaterColumns.java), decoded: the surface's height above the level's floor (0 when
// the column has no water), the lowest water block's, the underside of the first submerged ledge with water
// under it (0 for none), and the sky light (0 to 1) reaching the water above and below that ledge. Heights are
// in the map's frame (WaterColumnOrigin.y is the camera's).
struct WaterColumn {
    float surface;
    float bottom;
    float roof;
    float sky;
    float skyUnder;
};

bool waterColumnAt(vec3 p, out WaterColumn c) {
    c = WaterColumn(0.0, 0.0, 0.0, 0.0, 0.0);
    if (WaterColumnOrigin.w < 0.5 || max(abs(p.x), abs(p.z)) > WATER_COLUMN_RANGE) {
        return false;
    }
    ivec2 texel = ivec2(mod(floor(p.xz + WaterColumnOrigin.xz), 512.0));
    vec4 column = floor(texelFetch(WATER_COLUMNS, texel, 0) * 65535.0 + 0.5);
    c.surface = column.r / 16.0;
    c.bottom = mod(column.g, 4096.0);
    c.sky = floor(column.g / 4096.0) / 15.0;
    c.roof = mod(column.b, 4096.0);
    c.skyUnder = floor(column.b / 4096.0) / 15.0;
    return c.surface > 0.0;
}

// How far below the water's surface the camera-relative point p is (blocks), or -1 when it isn't in the top
// water body of its column (WaterColumns.java). It stands in for Bedrock's water front and back depths with
// the camera above water.
float depthBelowWaterSurface(vec3 p) {
    WaterColumn c;
    if (!waterColumnAt(p, c)) {
        return -1.0;
    }
    float y = p.y + WaterColumnOrigin.y;
    // Positions come from the view (with view bobbing's sway, up to about 0.1 blocks) and the height from the
    // camera without it, so floors lying exactly on the body's bottom get some slack: without it they flickered
    // in and out of the water while walking.
    return y >= c.bottom - 0.3 && y < c.surface ? max(c.surface - y, 0.0) : -1.0;
}

// The sky light (0 to 1) reaching the water at p (WaterColumns.java): full over open water, less under a ledge
// or a tree, by Minecraft's sky light beside it; under a submerged ledge, the sky light of the water beneath
// it. -1 outside the map or where it has no water.
float waterColumnSkyLight(vec3 p) {
    WaterColumn c;
    if (!waterColumnAt(p, c)) {
        return -1.0;
    }
    return c.roof > 0.0 && p.y + WaterColumnOrigin.y < c.roof ? c.skyUnder : c.sky;
}

// How much of the sky ambient light reaches water with sky light s (0 to 1). Bedrock lights its volume's
// ambient by per-froxel sky samples (PopulateVolume: SkyAmbientLightColorIntensity × the s_SkyAmbientSamples
// texture, or CameraAmbientContribution where those are off); this stands in with Minecraft's sky light,
// through the same curve as the terrain's sky ambient (lighting/deferred.fsh), so water under a ledge darkens
// as the ledge's floor does. 1 over open water, as before.
float waterSkyAccess(float s) {
    float cameraSky = sqrt(VolumeCamera.x) * 15.0 / 16.0;
    return s * mix(pow(s, 4.0), s * s, cameraSky);
}

// The point a visible surface's water test is made at: nudged back towards the camera, into the water in
// front of it (its face lies exactly on a block boundary, where the column or the body's bottom could go either
// way). Both passes use it, so they agree.
vec3 waterTestPoint(vec3 p) {
    return p - normalize(p) * 0.05;
}

// The point a lit surface's water test is made at, with its normal n: a quarter block out from its face along
// the face's axis (blocks' faces are axis-aligned; the snap ignores normal maps), into the water in front of
// it. A quarter block is more than view bobbing's offset between how this renderer's passes reconstruct a
// point (up to about a tenth of a block), which with a smaller nudge put a wall's points in the wall's own
// column at times (no water there) and left bright edges along underwater walls while walking.
vec3 waterTestPointOn(vec3 p, vec3 n) {
    vec3 a = abs(n);
    vec3 face = a.x > a.y && a.x > a.z ? vec3(sign(n.x), 0.0, 0.0)
            : a.y > a.z ? vec3(0.0, sign(n.y), 0.0) : vec3(0.0, 0.0, sign(n.z));
    return waterTestPoint(p) + face * 0.25;
}

// Bedrock's water volume (PopulateVolume's water medium, WaterAlbedoExtinction.w in the captures of the default
// water): 0.112 a block in every channel. Seen from above water, what lies under the surface reaches the camera
// through the scattering volume's transmittance (which holds this) and then the surface's WaterExtinction
// (DeferredWater), so both the floor and the light the water scatters fade at their sum: deep water settles at
// about the albedo times the light within a few blocks, and a shallow floor beside it is dimmed as much as
// Bedrock's (with the view extinction alone the blue built for some 25 blocks to several times Bedrock's level,
// and a drop-off showed as a hard seam).
const float WATER_VOLUME_EXTINCTION = 0.112349;

vec3 waterScatterExtinction() {
    return UnderwaterMedium.rgb + vec3(WATER_VOLUME_EXTINCTION);
}

// What's left of light the water scatters at p on its way out through the surface, along the view.
vec3 waterScatterTransmittanceTo(vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(waterTestPoint(p));
    if (depth < 0.0) {
        return vec3(1.0);
    }
    return exp(-waterScatterExtinction() * min(depth / max(-dir.y, 0.05), 64.0));
}

// The view's extinction alone (WaterExtinction) over the water above the point at p along the view: what
// DeferredWater applies to what lies under the surface, the volume's own 0.112 a block being the fog grid's
// (FroxelVolume) to apply as it integrates.
vec3 waterViewTransmittanceTo(vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(waterTestPoint(p));
    if (depth < 0.0) {
        return vec3(1.0);
    }
    return exp(-UnderwaterMedium.rgb * min(depth / max(-dir.y, 0.05), 64.0));
}

// As waterViewTransmittanceTo for a lit surface's chosen test point (waterTestPointOn) off the point p.
vec3 waterViewTransmittanceAt(vec3 testPoint, vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(testPoint);
    if (depth < 0.0) {
        return vec3(1.0);
    }
    depth = max(depth + testPoint.y - p.y, 0.0);
    return exp(-UnderwaterMedium.rgb * min(depth / max(-dir.y, 0.05), 64.0));
}

// As waterTransmittanceTo, for a test point already chosen (waterTestPointOn) off the surface point p: the
// test point says whether p is under the water, p how deep.
vec3 waterTransmittanceAt(vec3 testPoint, vec3 p, vec3 dir) {
    float depth = depthBelowWaterSurface(testPoint);
    if (depth < 0.0) {
        return vec3(1.0);
    }
    depth = max(depth + testPoint.y - p.y, 0.0);
    float path = min(depth / max(-dir.y, 0.05), 64.0);
    return exp(-waterScatterExtinction() * path);
}

// The water's extinction (the view's and the volume's) between its surface and the surface at p, along the view from the camera (above
// water), or 1 when p isn't under the mapped water.
vec3 waterTransmittanceTo(vec3 p, vec3 dir) {
    return waterTransmittanceAt(waterTestPoint(p), p, dir);
}

// The light the water scatters towards the camera over `path` blocks of it below the surface, along the view
// direction dir: what the lighting pass's march adds within the map (sky ambient over 4π and the sun or moon
// through the water's phase function, dimmed by the water in front of each point along the view), in closed
// form for a uniform layer, for water the map doesn't cover (farther out, and Distant Horizons' water). The
// sun's share is scaled by sunVisibility (whether what's beneath is in shade), the sky's by skyAccess (the
// camera's VolumeCamera.x, or waterSkyAccess where the water's sky light is known). This renderer's units,
// before exposure.
vec3 waterInScatter(vec3 dir, float path, float sunVisibility, float skyAccess) {
    bool moon = ShadowParams.w > 0.5;
    vec3 l = normalize(moon ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moon ? MoonColor.rgb : SunColor.rgb;
    float g = UnderwaterScattering.w;
    float cosine = dot(l, -dir);
    float phase = (1.0 - g * g) * 0.07957747 / pow(max(1.0 + g * (2.0 * cosine + g), 1e-4), 1.5);
    vec3 ambient = SkyColor.rgb * skyAccess * 0.07957747;
    vec3 sigma = waterScatterExtinction();
    return UnderwaterScattering.rgb * (ambient + light * phase * sunVisibility) * (1.0 - exp(-sigma * path)) / sigma;
}

#endif
