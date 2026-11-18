#ifndef BEDROCKVISUALS_FAR_CLOUDS_GLSL
#define BEDROCKVISUALS_FAR_CLOUDS_GLSL

// The cloud field traced along a ray (moved here from lighting/deferred.fsh): the lighting pass traces the
// clouds beyond Java's mesh with it, and the sky probe's base (sky/probe_base.fsh) the whole layer, as
// Bedrock draws its clouds (CloudsForwardPBR) into its reflection probe before lighting it. Needs
// cloud_lighting.glsl, and the including shader to define CLOUD_COVERAGE (the cells' filtered share,
// CloudShadows.java) as a sampler name. meshEdge: where Java's mesh hands over (0: no mesh, the whole layer).

// Bedrock's clouds beyond Java's mesh: the view ray is traced through the cloud field (the same cells Java's
// mesh is built from, as they drift) out to where Bedrock's cloud fog, saturate(distance / DistanceControl.x
// - 0.9), has blended them into the sky. They fade in exactly where the mesh fades out, and are lit like the
// mesh's faces (cloud_lighting.glsl). Near, the first filled cell the ray meets is found exactly; far, where
// a pixel spans many cells (one hit per pixel would sparkle), the share of cloud along the ray's path through
// the layer is read from the filtered coverage instead. rgb: colour (this renderer's units), a: coverage;
// nothing past maxDistance. footprint: the angle one pixel spans.
const int FAR_CLOUD_STEPS = 64;

struct FarCloudRay {
    vec3 origin;  // the camera in the field (x, z in blocks; y from the layer's bottom)
    float start;  // where the far clouds begin along the ray
    float end;    // and end (the layer, maxDistance, or where the fog has made them sky)
    float leave;  // where the ray leaves the layer
    float edge;   // the mesh's edge, horizontally
    float far;    // Bedrock's cloud distance
    float horizontal;
};

vec3 farCloudShade(vec3 dir, vec3 n, float t, float viewPath, float lightPath, float far) {
    vec3 color = cloudLight(dir, n, viewPath, lightPath);
    float fog = clamp(t / far - 0.9, 0.0, 1.0);
    return mix(color, bedrockSkyRadiance(dir, normalize(SunDirection.xyz), normalize(MoonDirection.xyz))
            * AtmosphereHorizon.w, fog);
}

vec4 farCloudsTraced(vec3 dir, FarCloudRay r) {
    vec3 p = r.origin + dir * r.start;
    ivec2 cell = ivec2(floor(p.xz / CLOUD_CELL));
    vec2 d = dir.xz;
    ivec2 stepDir = ivec2(sign(d));
    vec2 safe = vec2(abs(d.x) > 1e-6 ? d.x : 1e-6, abs(d.y) > 1e-6 ? d.y : 1e-6);
    vec2 tDelta = abs(vec2(CLOUD_CELL) / safe);
    vec2 tNext = r.start + ((vec2(cell) + step(0.0, d)) * CLOUD_CELL - p.xz) / safe;
    tNext = mix(vec2(1e9), tNext, greaterThan(abs(d), vec2(1e-6)));
    float t = r.start;
    // The face the ray enters by: the layer's top or bottom at first, then the side of the last cell edge.
    vec3 n = vec3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0);
    bool moonLights = ShadowParams.w > 0.5;
    vec3 l = normalize(moonLights ? MoonDirection.xyz : SunDirection.xyz);
    for (int i = 0; i < FAR_CLOUD_STEPS; i++) {
        if (t >= r.end) {
            return vec4(0.0);
        }
        if (cellFilled(cell)) {
            vec3 hit = r.origin + dir * t;
            float viewPath = cloudPath(hit + dir * 0.01, dir);
            float lightPath = cloudPath(hit + dir * (viewPath * 0.5 + 0.01), l);
            float fadeIn = r.edge > 0.0 ? smoothstep(0.75 * r.edge, r.edge, t * r.horizontal) : 1.0;
            return vec4(farCloudShade(dir, n, t, viewPath, lightPath, r.far), CLOUD_OPACITY * fadeIn);
        }
        if (tNext.x < tNext.y) {
            t = tNext.x;
            tNext.x += tDelta.x;
            cell.x += stepDir.x;
            n = vec3(-float(stepDir.x), 0.0, 0.0);
        } else {
            t = tNext.y;
            tNext.y += tDelta.y;
            cell.y += stepDir.y;
            n = vec3(0.0, 0.0, -float(stepDir.y));
        }
    }
    return vec4(0.0);
}

vec4 farCloudsFiltered(vec3 dir, FarCloudRay r, float cellsPerPixel) {
    // The ray's path through the layer, as far as the far clouds go, and the cloud's share around its middle
    // at the pixel's footprint. A path crossing several cells meets cloud more often than one point does.
    float through = max(min(r.leave, r.end) - r.start, 0.0);
    float t = r.start + 0.5 * through;
    vec2 size = CloudShadowSize.xy;
    vec3 p = r.origin + dir * t;
    float lod = log2(max(cellsPerPixel, 1.0));
    float share = textureLod(CLOUD_COVERAGE, p.xz / (CLOUD_CELL * size), lod).r;
    float cellsCrossed = through * r.horizontal / CLOUD_CELL;
    float hit = 1.0 - pow(1.0 - clamp(share, 0.0, 0.999), 1.0 + cellsCrossed);
    if (hit <= 0.0) {
        return vec4(0.0);
    }
    bool moonLights = ShadowParams.w > 0.5;
    vec3 l = normalize(moonLights ? MoonDirection.xyz : SunDirection.xyz);
    vec3 n = vec3(0.0, dir.y > 0.0 ? -1.0 : 1.0, 0.0);
    // Typical paths inside a cloud: along the view through the layer, the light through its height.
    float viewPath = min(through, MAX_PATH);
    float lightPath = min(CLOUD_HEIGHT * 0.5 / max(abs(l.y), 0.05), MAX_PATH);
    float fadeIn = r.edge > 0.0 ? smoothstep(0.75 * r.edge, r.edge, r.start * r.horizontal) : 1.0;
    return vec4(farCloudShade(dir, n, t, viewPath, lightPath, r.far), CLOUD_OPACITY * hit * fadeIn);
}

vec4 farCloudsBeyond(vec3 dir, float maxDistance, float footprint, float meshEdge) {
    if (FarClouds.w < 0.5 || AtmosphereZenith.w < 0.5 || CloudShadowSize.w < 0.5) {
        return vec4(0.0);
    }
    float bottom = CloudShadowField.z - CLOUD_HEIGHT * 0.5; // the layer's bottom, relative to the camera
    float top = bottom + CLOUD_HEIGHT;
    FarCloudRay r;
    r.horizontal = length(dir.xz);
    if (r.horizontal < 1e-4) {
        return vec4(0.0); // straight up or down: Java's mesh covers it
    }
    float enter;
    if (abs(dir.y) < 1e-5) {
        if (bottom > 0.0 || top < 0.0) {
            return vec4(0.0);
        }
        enter = 0.0;
        r.leave = 1e9;
    } else {
        float a = bottom / dir.y;
        float b = top / dir.y;
        enter = max(min(a, b), 0.0);
        r.leave = max(a, b);
    }
    r.edge = meshEdge;
    r.far = max(FarClouds.y, 1.0);
    // Past 1.9 × the cloud distance Bedrock's fog has made them all sky.
    r.start = max(enter, 0.75 * r.edge / r.horizontal);
    r.end = min(min(r.leave, maxDistance), 1.9 * r.far / r.horizontal);
    if (r.start >= r.end) {
        return vec4(0.0);
    }
    r.origin = vec3(CloudShadowField.x, -bottom, CloudShadowField.y);
    // Cells one pixel spans where the ray reaches the layer, along its slant: seen edge-on, a pixel stretches
    // across the layer by 1 / sin(elevation).
    float cellsPerPixel = r.start * footprint / (CLOUD_CELL * max(abs(dir.y), 0.02));
    float blend = smoothstep(0.35, 0.7, cellsPerPixel);
    vec4 traced = blend < 1.0 ? farCloudsTraced(dir, r) : vec4(0.0);
    vec4 filtered = blend > 0.0 ? farCloudsFiltered(dir, r, cellsPerPixel) : vec4(0.0);
    float a = mix(traced.a, filtered.a, blend);
    vec3 rgb = mix(traced.rgb * traced.a, filtered.rgb * filtered.a, blend) / max(a, 1e-5);
    return vec4(rgb, a);
}

#endif
