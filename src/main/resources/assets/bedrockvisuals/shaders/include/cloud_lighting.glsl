#ifndef BEDROCKVISUALS_CLOUD_LIGHTING_GLSL
#define BEDROCKVISUALS_CLOUD_LIGHTING_GLSL

// Bedrock's cloud lighting (CloudsForwardPBR, see docs/bedrock-renderer-notes.md, "Clouds"), shared by the
// clouds on Java's mesh (clouds/clouds.fsh) and the far clouds the lighting pass traces beyond it. Needs
// common.glsl and sky.glsl, and the including shader to define CLOUD_CELLS (the filled-cells texture,
// CloudShadows.java) and CLOUD_BRDF (Bedrock's BRDF table) as sampler names.

// Bedrock's CloudLightingUniforms and diffuse wrap, from GPU captures (the same at every time of day):
// x: thickness (blocks) over which the scattered and back light fade, y: Henyey-Greenstein g, z: reflected sky,
// w: extinction per block along the light's path.
const vec4 CLOUD_LIGHTING = vec4(16.0, 0.5, 5.0, 0.0625);
const float DIFFUSE_WRAP = 0.307;
// CloudColor.a in the captures.
const float CLOUD_OPACITY = 0.9;
// Bedrock caps the paths at 16 blocks.
const float MAX_PATH = 16.0;
// Java's (and Bedrock's) cloud cells: 12 blocks across, 4 tall.
const float CLOUD_CELL = 12.0;
const float CLOUD_HEIGHT = 4.0;

bool cellFilled(ivec2 cell) {
    ivec2 size = ivec2(CloudShadowSize.xy);
    ivec2 wrapped = cell - size * ivec2(floor(vec2(cell) / vec2(size)));
    return texelFetch(CLOUD_CELLS, wrapped, 0).r > 0.5;
}

// Distance from p along d until the ray leaves the cloud, capped at MAX_PATH: out of the layer's top or
// bottom, or into an empty cell. p is in the cloud field (x, z in blocks; y from the layer's bottom). Walks
// the cell grid, so a path runs on through neighbouring filled cells as Bedrock's does; per-cell paths would
// light every cell on its own and show the grid.
float cloudPath(vec3 p, vec3 d) {
    float tMax = MAX_PATH;
    if (d.y > 1e-5) {
        tMax = min(tMax, (CLOUD_HEIGHT - p.y) / d.y);
    } else if (d.y < -1e-5) {
        tMax = min(tMax, -p.y / d.y);
    }
    tMax = max(tMax, 0.0);
    ivec2 cell = ivec2(floor(p.xz / CLOUD_CELL));
    vec2 dir = d.xz;
    ivec2 stepDir = ivec2(sign(dir));
    vec2 safeDir = vec2(abs(dir.x) > 1e-6 ? dir.x : 1e-6, abs(dir.y) > 1e-6 ? dir.y : 1e-6);
    vec2 tDelta = abs(vec2(CLOUD_CELL) / safeDir);
    vec2 nextEdge = (vec2(cell) + step(0.0, dir)) * CLOUD_CELL;
    vec2 tNext = (nextEdge - p.xz) / safeDir;
    tNext = mix(vec2(1e9), tNext, greaterThan(abs(dir), vec2(1e-6)));
    float t = 0.0;
    // 16 blocks cross at most 3 cell edges.
    for (int i = 0; i < 4; i++) {
        if (!cellFilled(cell)) {
            return t;
        }
        float edge = min(tNext.x, tNext.y);
        if (edge >= tMax) {
            return tMax;
        }
        t = edge;
        if (tNext.x < tNext.y) {
            tNext.x += tDelta.x;
            cell.x += stepDir.x;
        } else {
            tNext.y += tDelta.y;
            cell.y += stepDir.y;
        }
    }
    return min(t, tMax);
}

// A cloud face's colour (this renderer's units, before exposure): view runs from the camera to the face, n
// the face's normal, viewPath the view's path inside the cloud from the face, lightPath the light's path from
// the middle of that.
vec3 cloudLight(vec3 view, vec3 n, float viewPath, float lightPath) {
    bool moonLights = ShadowParams.w > 0.5;
    vec3 l = normalize(moonLights ? MoonDirection.xyz : SunDirection.xyz);
    vec3 light = moonLights ? MoonColor.rgb : SunColor.rgb;
    // Bedrock's cloud colour: white by day, (0.1, 0.1, 0.15) at midnight in the captures, greyed by rain and
    // thunder (Weather.java). Java's own CloudColor isn't used.
    vec3 albedo = VolumeCamera.yzw;

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

    // Reflected sky: Bedrock's probe at its roughest mip times its BRDF table at roughness 1 and F0 0.04.
    if (AtmosphereZenith.w > 0.5) {
        float nDotV = clamp(dot(n, -view), 0.0, 1.0);
        float fresnel;
        if (DebugParams.z > 0.5) {
            vec2 ab = texture(CLOUD_BRDF, vec2(nDotV, 0.0)).rg;
            fresnel = 0.04 * ab.x + ab.y;
        } else {
            // The table's roughness-1 row, roughly.
            fresnel = 0.04 * mix(0.57, 0.31, nDotV) + 0.016 * (1.0 - nDotV);
        }
        color += CLOUD_LIGHTING.z * fresnel * bedrockSkyReflection(reflect(view, n), 1.0, normalize(SunDirection.xyz),
                normalize(MoonDirection.xyz));
    }
    return color;
}

#endif
