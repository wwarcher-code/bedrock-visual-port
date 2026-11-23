package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.render.MaterialAtlas;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;

/**
 * Ray-traced diffuse light for the terrain, cached per texel (TexelCache):
 * <ul>
 * <li><b>Light from emissive texels</b> (EmissiveLights), {@link Mode}: at each texel, one light picked from its
 * grid cell's list in proportion to how much it would give the point unshadowed, a random point of that light's
 * quad, its texel's glow there (the block atlas colour times Bedrock's emissive, times 2, as the lighting pass
 * draws emissive surfaces), and a shadow ray to it. Soft, coloured light whose shadows widen with distance from
 * the light. It goes in place of Bedrock's block light ({@link Mode#REPLACE}) or over a share of it
 * ({@link Mode#FLOOR}).</li>
 * <li><b>Bounce light</b> ({@link #setBounce}): a ray in a cosine-weighted direction from the texel; where it
 * meets terrain or an entity, the light that surface sends back: the sun or moon on it (with its own shadow ray)
 * and the light cached at its texel (emitted and bounced), times its colour. The cache feeding itself gives
 * more bounces over the frames. Sky light isn't bounced: Bedrock's sky ambient term stays as it is. It adds to
 * the lighting pass's indirect light.</li>
 * </ul>
 *
 * <p>Three stages (FrameCompute): a thread a pixel adds its samples to its texel's entry, a thread an entry
 * blends them into its history, and a thread a pixel reads its texel's history into {@link #view()}: RGBA16F,
 * twice the G-buffer's width, the emitted light (alpha 1 on a stand-in light's own surface, which keeps
 * Bedrock's block light) on the left half and the bounced light on the right.
 */
public final class RtEmissive {
	public enum Mode {
		/** Bedrock's block light, as before. */
		OFF,
		/** The ray-traced light in place of Bedrock's block light. */
		REPLACE,
		/** The ray-traced light, but never darker than Bedrock's block light times the floor. */
		FLOOR
	}

	/** Bindings, push constants and functions every stage shares. */
	static final String COMMON = """
			layout(set = 0, binding = 0) uniform accelerationStructureEXT scene;
			struct InstanceInfo { uvec2 vertices; uint kind; uint stride; uint firstVertex[3]; uint passes; };
			layout(set = 0, binding = 1, std430) readonly buffer Instances { InstanceInfo instances[]; };
			layout(set = 0, binding = 2) uniform sampler2D Depth;
			layout(set = 0, binding = 3) uniform sampler2D Normal;
			layout(set = 0, binding = 4) uniform sampler2D Atlas;
			layout(set = 0, binding = 5) uniform sampler2D Materials;
			struct Light { vec4 p0; vec4 e1; vec4 e2; vec4 uv; vec4 uv2; };
			layout(set = 0, binding = 6, std430) readonly buffer Lights { Light lights[]; };
			// About how many of a texel's pixels trace samples into it each frame (TRACE).
			const float TEXEL_SAMPLES = 16.0;
			// Per cell, PER_CELL + 1 vec4s (its count; then each light's centre and power); then each entry's light
			// index, the top bit set for a light that stands in for block light (EmissiveLights).
			layout(set = 0, binding = 7, std430) readonly buffer Cells { vec4 cells[]; };
			layout(set = 0, binding = 7, std430) readonly buffer CellIndices { uint cellWords[]; };
			#define CACHE_BINDING 8
			layout(set = 0, binding = 9, std430) readonly buffer Frame {
			    vec4 towardsLight;  // the sun, or the moon at night (the light that casts this frame's shadows)
			    vec4 lightColor;    // its light, as the lighting pass's sunLight() or moonLight()
			    vec4 switches;      // x: emissive light on, y: bounce on, z: the bounce's gain, w: the emissive's gain
			    ivec4 changeCount;  // x: how many places the world changed since the last frame (TexelCache)
			    ivec4 changes[32]; // per change: the lowest and the highest world block it reaches
			    ivec4 entityCount;  // x: how many lights entities carry this frame (EntityLights); y: 1 if the scene
			                        // has entities at all (their shadows over the emitted light are traced apart);
			                        // z: 1 if the light volume is there (EntityProbes' solid blocks)
			    vec4 entityLights[64]; // per light: its position (relative to the scene's origin) and radius; its
			                           // colour times its strength, and its reach
			    vec4 skyBounceLight;   // rgb: the sky's light (Bedrock's sky ambient, LightingState.sky); w: 1 when
			                           // bounce rays that reach the sky bring it back (in place of Bedrock's term)
			};
			layout(set = 0, binding = OUTPUT_BINDING, std430) writeonly buffer Output { uvec2 pixels[]; };
			layout(buffer_reference, scalar, buffer_reference_align = 4) readonly buffer Words { uint w[]; };
			layout(push_constant) uniform Params {
			    mat4 inverseViewProjection; // clip space to camera-relative world space (the lighting pass's)
			    vec4 camera;                // xyz: the camera relative to the scene's origin; w: pixels a block spans one
			                                // block away (0 in exports: every pixel traces)
			    vec4 grid;                  // xyz: the scene's origin relative to the light grid's corner; w: samples
			    uvec4 size;                 // xy: the G-buffer's size; z: the frame number
			    ivec4 originBlock;          // xyz: the scene's origin in world blocks; w: samples a texel's history holds
			};

			const int CELLS_X = 32; // EmissiveLights.CELLS_X and so on
			const int CELLS_Y = 16;
			const int CELLS_Z = 32;
			const int PER_CELL = 96; // EmissiveLights.PER_CELL
			const float CELL = 8.0;
			const float ALPHA_CUTOFF[4] = float[4](0.0, 0.1, 0.5, 1.0);
			const float PI = 3.14159265;
			// EmissiveLights' CUTOFF and MAX_REACH: a light's reach from its power.
			const float LIGHT_CUTOFF = 0.004;
			const float LIGHT_MAX_REACH = 32.0;
			float reachOf(float power) {
			    return min(sqrt(power / LIGHT_CUTOFF), LIGHT_MAX_REACH);
			}
			// A light fades smoothly to nothing at its reach, (1 - (d / reach)^4)^2: its light used to stop there,
			// and where the jittered cell lookup only sometimes listed it, the edge was a big spotted patch
			// (bright under auto exposure in a dark room).
			float reachWindow(float d, float reach) {
			    float r = d / max(reach, 0.001);
			    float f = clamp(1.0 - r * r * r * r, 0.0, 1.0);
			    return f * f;
			}

			vec2 sodiumUv(Words words, uint vertex) {
			    uint packed = words.w[vertex * 5u + 3u];
			    return vec2(packed & 0x7FFFu, (packed >> 16u) & 0x7FFFu) / 32768.0;
			}

			vec3 sodiumPosition(Words words, uint vertex) {
			    uint hi = words.w[vertex * 5u];
			    uint lo = words.w[vertex * 5u + 1u];
			    uvec3 shift = uvec3(0u, 10u, 20u);
			    uvec3 value = ((uvec3(hi) >> shift) & 0x3FFu) << 10u | ((uvec3(lo) >> shift) & 0x3FFu);
			    return vec3(value) * (32.0 / 1048576.0) - 8.0;
			}

			vec3 entityPosition(Words words, uint vertex) {
			    uint at = vertex * 9u;
			    return vec3(uintBitsToFloat(words.w[at]), uintBitsToFloat(words.w[at + 1u]), uintBitsToFloat(words.w[at + 2u]));
			}

			// Whether a candidate hit stops a ray: cutout terrain alpha-tested against the block atlas with Sodium's
			// cutoff, translucent terrain letting it through (as in the shadow maps), entities stopping it.
			""" + RtShadows.FAR_FIELD_BLOCKS + """
			bool blocks(rayQueryEXT query) {
			    InstanceInfo info = instances[rayQueryGetIntersectionInstanceCustomIndexEXT(query, false)];
			    if (info.kind == 2u) {
			        return farFieldBlocks(query, info);
			    }
			    if (info.kind != 0u) {
			        return true;
			    }
			    uint geometry = uint(rayQueryGetIntersectionGeometryIndexEXT(query, false));
			    uint pass = (info.passes >> (2u * geometry)) & 3u;
			    if (pass != 1u) {
			        return pass == 0u;
			    }
			    uint primitive = uint(rayQueryGetIntersectionPrimitiveIndexEXT(query, false));
			    uvec3 corners = (primitive & 1u) == 0u ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u);
			    uint first = info.firstVertex[geometry] + (primitive >> 1u) * 4u;
			    Words words = Words(info.vertices);
			    vec2 b = rayQueryGetIntersectionBarycentricsEXT(query, false);
			    vec2 uv = sodiumUv(words, first + corners.x) * (1.0 - b.x - b.y) + sodiumUv(words, first + corners.y) * b.x
			            + sodiumUv(words, first + corners.z) * b.y;
			    uint material = (words.w[first * 5u + 4u] >> 16u) & 0xFFu;
			    return textureLod(Atlas, uv, 0.0).a >= ALPHA_CUTOFF[(material >> 1u) & 3u];
			}

			// Whether anything among the instances in mask (RtScene's MASK_TERRAIN, MASK_ENTITIES) stops a ray.
			bool occludedBy(vec3 origin, vec3 direction, float tMax, uint mask) {
			    rayQueryEXT query;
			    rayQueryInitializeEXT(query, scene, gl_RayFlagsTerminateOnFirstHitEXT, mask, origin, 0.001, direction, tMax);
			    while (rayQueryProceedEXT(query)) {
			        if (rayQueryGetIntersectionTypeEXT(query, false) == gl_RayQueryCandidateIntersectionTriangleEXT
			                && blocks(query)) {
			            rayQueryConfirmIntersectionEXT(query);
			        }
			    }
			    return rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
			}

			bool entitiesNear;
			""" + RtShadows.TRANSLUCENT_TINT + """
			// The light a ray carries through the instances in mask: none if something stops it, else the colour
			// the translucent terrain on its way lets through (RtShadows.TRANSLUCENT_TINT).
			vec3 transmittance(vec3 origin, vec3 direction, float tMax, uint mask) {
			    vec3 through = vec3(1.0);
			    rayQueryEXT query;
			    rayQueryInitializeEXT(query, scene, gl_RayFlagsTerminateOnFirstHitEXT, mask, origin, 0.001, direction, tMax);
			    while (rayQueryProceedEXT(query)) {
			        if (rayQueryGetIntersectionTypeEXT(query, false) == gl_RayQueryCandidateIntersectionTriangleEXT) {
			            if (blocks(query)) {
			                rayQueryConfirmIntersectionEXT(query);
			            } else {
			                through *= translucentTint(query);
			            }
			        }
			    }
			    return rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT ? vec3(0.0)
			            : through;
			}

			// Whether anything stops a ray before tMax.
			bool occluded(vec3 origin, vec3 direction, float tMax) {
			    return occludedBy(origin, direction, tMax, 0xFFu);
			}

			uint state;
			float random() {
			    state = state * 747796405u + 2891336453u;
			    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
			    return float((word >> 22u) ^ word) / 4294967296.0;
			}

			// Three numbers in [0, 1) for this pixel's sample `stream` this frame: the R3 low-discrepancy sequence
			// over frames, offset by the pixel (a Cranley-Patterson rotation). Each pixel's samples spread evenly over
			// the frames instead of clumping, so its texel's average settles with less shimmer than with independent
			// random numbers; the offsets keep the pixels of a texel from all taking the same samples.
			uint sequencePixel;
			vec3 sequence3(uint stream) {
			    uint seed = sequencePixel * 0x9e3779b9u ^ (stream + 1u) * 0x85ebca6bu;
			    seed ^= seed >> 16u; seed *= 0x7feb352du; seed ^= seed >> 15u; seed *= 0x846ca68bu; seed ^= seed >> 16u;
			    vec3 offset = vec3(seed & 0x3FFu, (seed >> 10u) & 0x3FFu, (seed >> 20u) & 0x3FFu) / 1024.0;
			    float n = float(size.z % 4096u);
			    return fract(offset + n * vec3(0.8191725134, 0.6710436067, 0.5497004779));
			}
			""" + TexelCache.GLSL + """

			// The pixel's surface: false for sky. p: its point relative to the scene's origin, at its texel's centre
			// (at its level) on axis-aligned faces; n its normal; thinSheet for crossed plants.
			bool surface(uvec2 pixel, out vec3 p, out vec3 n, out bool thinSheet, out int level) {
			    float depth = texelFetch(Depth, ivec2(pixel), 0).r;
			    if (depth <= 0.0) {
			        return false;
			    }
			    vec2 texCoord = (vec2(pixel) + 0.5) / vec2(size.xy);
			    n = normalize(texelFetch(Normal, ivec2(pixel), 0).rgb * 2.0 - 1.0);
			    vec4 world = inverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
			    vec3 relative = world.xyz / world.w;
			    vec3 a = abs(n);
			    thinSheet = max(a.x, max(a.y, a.z)) < 0.9;
			    level = texelLevel(length(relative));
			    p = texelCentre(relative + camera.xyz, n, thinSheet, level);
			    return true;
			}

			// The light entity light i gives point p (normal n), unshadowed, from its centre, in the cache's units:
			// its strength over the distance squared (held off within a quarter block), by the cosine at p, faded
			// to nothing at its reach.
			vec3 entityLight(uint i, vec3 p, vec3 n, bool thinSheet) {
			    vec4 at = entityLights[i * 2u];
			    vec4 light = entityLights[i * 2u + 1u];
			    vec3 toLight = at.xyz - p;
			    float d2 = dot(toLight, toLight);
			    float reach2 = light.w * light.w;
			    if (d2 >= reach2) {
			        return vec3(0.0);
			    }
			    float cosReceiver = dot(n, toLight) * inversesqrt(max(d2, 1e-6));
			    cosReceiver = thinSheet ? abs(cosReceiver) : max(cosReceiver, 0.0);
			    // The same fade as the block lights' (reachWindow), so a dropped torch lights as the placed one does.
			    // Held off within the light's radius (at least a quarter block).
			    return light.rgb * cosReceiver * reachWindow(sqrt(d2), light.w) / max(d2, max(0.0625, at.w * at.w))
			            * switches.w / PI;
			}

			// What a bounce ray that reaches the sky brings back: nothing, as Bedrock's sky ambient term has the sky's
			// light; or, with the sky bounce on, the sky's light (Bedrock's sky ambient colour, as radiance: a surface
			// open to the whole sky gets what Bedrock's term gives it), from above the horizon, through any glass.
			// Sky light brought back through water fades by this a block (TUNE: about Bedrock's water fog).
			const float WATER_SKY_EXTINCTION = 0.15;
			// Whether a candidate is a water face (translucent terrain Sodium tints by biome: TRANSLUCENT_TINT).
			bool waterFace(rayQueryEXT query) {
			    InstanceInfo info = instances[rayQueryGetIntersectionInstanceCustomIndexEXT(query, false)];
			    uint geometry = uint(rayQueryGetIntersectionGeometryIndexEXT(query, false));
			    if (info.kind != 0u || ((info.passes >> (2u * geometry)) & 3u) != 2u) {
			        return false;
			    }
			    uint primitive = uint(rayQueryGetIntersectionPrimitiveIndexEXT(query, false));
			    uint first = info.firstVertex[geometry] + (primitive >> 1u) * 4u;
			    vec3 tint = unpackUnorm4x8(Words(info.vertices).w[first * 5u + 2u]).rgb;
			    return max(tint.r, max(tint.g, tint.b)) - min(tint.r, min(tint.g, tint.b)) > 0.06;
			}

			vec3 skyBounce(vec3 direction, vec3 tint) {
			    return skyBounceLight.w > 0.5 && direction.y > 0.0 ? skyBounceLight.rgb * tint : vec3(0.0);
			}

			// The light a bounce ray from origin sends back from the surface it meets: the sun or moon on it (its own
			// shadow ray) and the light cached at its texel, times its colour; none for the sky (Bedrock's ambient
			// term has it). In the cache's units (light over pi).
			vec3 bounceRadiance(vec3 origin, vec3 direction) {
			    rayQueryEXT query;
			    rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, 0x01, origin, 0.001, direction, 256.0);
			    // Whether the ray crossed translucent terrain on the way (stained glass): then its light is tinted.
			    bool crossed = false;
			    // How far the ray went before leaving water (its surface from below; 0 if it met none): sky light
			    // brought back through water is dimmed by it.
			    float inWater = 0.0;
			    while (rayQueryProceedEXT(query)) {
			        if (rayQueryGetIntersectionTypeEXT(query, false) == gl_RayQueryCandidateIntersectionTriangleEXT) {
			            if (blocks(query)) {
			                rayQueryConfirmIntersectionEXT(query);
			            } else {
			                crossed = true;
			                if (waterFace(query)) {
			                    inWater = max(inWater, rayQueryGetIntersectionTEXT(query, false));
			                }
			            }
			        }
			    }
			    if (rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionTriangleEXT) {
			        vec3 tint = crossed ? transmittance(origin, direction, 1024.0, 0x01u) : vec3(1.0);
			        return skyBounce(direction, tint * exp(-WATER_SKY_EXTINCTION * inWater));
			    }
			    // The glass between: the colour it lets through to the surface met (a second ray, only when crossed).
			    vec3 through = crossed ? transmittance(origin, direction,
			            max(rayQueryGetIntersectionTEXT(query, true) - 0.01, 0.002), 0x01u) : vec3(1.0);
			    // The surface met: its position, facing normal and colour.
			    InstanceInfo info = instances[rayQueryGetIntersectionInstanceCustomIndexEXT(query, true)];
			    uint geometry = uint(rayQueryGetIntersectionGeometryIndexEXT(query, true));
			    uint primitive = uint(rayQueryGetIntersectionPrimitiveIndexEXT(query, true));
			    uvec3 corners = (primitive & 1u) == 0u ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u);
			    Words words = Words(info.vertices);
			    vec2 b = rayQueryGetIntersectionBarycentricsEXT(query, true);
			    vec3 hit = origin + direction * rayQueryGetIntersectionTEXT(query, true);
			    vec3 v0;
			    vec3 v1;
			    vec3 v2;
			    vec3 albedo;
			    bool terrain = info.kind == 0u;
			    if (terrain) {
			        uint first = info.firstVertex[geometry] + (primitive >> 1u) * 4u;
			        v0 = sodiumPosition(words, first + corners.x);
			        v1 = sodiumPosition(words, first + corners.y);
			        v2 = sodiumPosition(words, first + corners.z);
			        vec2 uv = sodiumUv(words, first + corners.x) * (1.0 - b.x - b.y) + sodiumUv(words, first + corners.y) * b.x
			                + sodiumUv(words, first + corners.z) * b.y;
			        // The block's texture times Sodium's vertex colour (biome tint and ambient occlusion).
			        albedo = pow(textureLod(Atlas, uv, 0.0).rgb, vec3(2.2)) * unpackUnorm4x8(words.w[first * 5u + 2u]).rgb;
			    } else {
			        uint first = (primitive >> 1u) * 4u;
			        v0 = entityPosition(words, first + corners.x);
			        v1 = entityPosition(words, first + corners.y);
			        v2 = entityPosition(words, first + corners.z);
			        // Entity textures aren't in the block atlas: the vertex colour, which EntityGeometry multiplied by the
			        // texture's mean linear colour (a middling grey where the texture couldn't be read).
			        albedo = unpackUnorm4x8(words.w[first * 9u + 3u]).rgb;
			    }
			    vec3 hitNormal = normalize(cross(v1 - v0, v2 - v0));
			    if (dot(hitNormal, direction) > 0.0) {
			        hitNormal = -hitNormal;
			    }
			    vec3 light = vec3(0.0);
			    // The sun or moon on it.
			    vec3 towards = normalize(towardsLight.xyz);
			    float nDotL = dot(hitNormal, towards);
			    if (nDotL > 0.0 && dot(lightColor.rgb, vec3(1.0)) > 0.0) {
			        light += albedo / PI * lightColor.rgb * nDotL
			                * transmittance(hit + hitNormal * 0.01, towards, 1024.0, 0x01u);
			    }
			    // And the light cached at its texel (emitted and bounced): the cache feeding itself. Its fast average
			    // (TexelCache), not the slow one shown: light passed on from surface to surface at the slow average's pace
			    // took seconds to settle after a change, a second more for every bounce; the receiving texel's own
			    // average smooths what the fast one adds in noise.
			    if (terrain) {
			        int level = texelLevel(length(hit - camera.xyz));
			        vec3 centre = texelCentre(hit, hitNormal, false, level);
			        int slot = findTexel(texelKey(centre, hitNormal, false, originBlock.xyz, level), false);
			        if (slot >= 0) {
			            light += albedo * (entries[slot].fastA.rgb * switches.x + entries[slot].fastB.rgb);
			        }
			    }
			    // The lights entities carry on it too (unshadowed: a bounce of a carried torch off the floor around it).
			    uint carriedLights = uint(entityCount.x);
			    for (uint i = 0u; i < carriedLights; i++) {
			        light += albedo * entityLight(i, hit, hitNormal, false);
			    }
			    return light * through * switches.z;
			}

			// The light grid cell at g: its first vec4 and first index word, or false outside the grid.
			bool gridCell(vec3 g, out uint base, out uint indexBase) {
			    ivec3 cell = ivec3(floor(g / CELL));
			    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(CELLS_X, CELLS_Y, CELLS_Z)))) {
			        return false;
			    }
			    uint cellIndex = uint((cell.z * CELLS_Y + cell.y) * CELLS_X + cell.x);
			    base = cellIndex * uint(PER_CELL + 1);
			    indexBase = uint(CELLS_X * CELLS_Y * CELLS_Z * (PER_CELL + 1)) * 4u + cellIndex * uint(PER_CELL);
			    return true;
			}
			""";

	private static final String GROUPS_8X8 = """
			#version 460
			#extension GL_EXT_ray_query : require
			#extension GL_EXT_buffer_reference : require
			#extension GL_EXT_buffer_reference_uvec2 : require
			#extension GL_EXT_scalar_block_layout : require
			layout(local_size_x = 8, local_size_y = 8) in;
			#define OUTPUT_BINDING 10
			""";

	/** Stage 1, a thread a pixel: samples of the light at the pixel's texel, added to its cache entry. */
	private static final String TRACE = GROUPS_8X8 + COMMON + """
			// One sample of the light from emissive texels at point p (normal n), given the cell's lights' running
			// totals of weight (cumulative[i]: lights 0 to i): as the terrain lets it through (an entity in the way
			// sets shadowed instead, for TexelCache's C).
			// The direction the last emissiveSample's and bounceSample's light came from (for TexelCache's E).
			vec3 emittedFrom;
			vec3 bouncedFrom;
			vec3 emissiveSample(vec3 p, vec3 g, vec3 n, bool thinSheet, uint count, uint indexBase,
			        float cumulative[PER_CELL], float total, vec3 u, out bool shadowed) {
			    shadowed = false;
			    if (total <= 0.0) {
			        return vec3(0.0);
			    }
			    // The first light whose running total passes the pick: halving the range, 7 steps for 96 lights
			    // instead of a walk through them for every sample.
			    float pick = u.x * total;
			    uint low = 0u;
			    uint high = count - 1u;
			    while (low < high) {
			        uint middle = (low + high) / 2u;
			        if (cumulative[middle] < pick) {
			            low = middle + 1u;
			        } else {
			            high = middle;
			        }
			    }
			    uint chosen = low;
			    float probability = (cumulative[chosen] - (chosen > 0u ? cumulative[chosen - 1u] : 0.0)) / total;
			    if (probability <= 0.0) {
			        return vec3(0.0);
			    }
			    Light light = lights[cellWords[indexBase + chosen] & 0x7FFFFFFFu];
			    // A random point of it, and its texel's glow.
			    float s = u.y;
			    float t = u.z;
			    vec3 x = light.p0.xyz + light.e1.xyz * s + light.e2.xyz * t;
			    vec2 uv = light.uv.xy + light.uv.zw * s + light.uv2.xy * t;
			    vec4 texel = textureLod(Atlas, uv, 0.0);
			    float emissive = max(textureLod(Materials, uv, 0.0).g, light.uv2.z);
			    if (emissive <= 0.0 || (light.e2.w > 0.0 && texel.a < light.e2.w)) {
			        return vec3(0.0);
			    }
			    vec3 toPoint = x - g;
			    float d = length(toPoint);
			    float window = reachWindow(d, light.e1.w);
			    if (d < 0.05 || window <= 0.0) {
			        return vec3(0.0);
			    }
			    vec3 direction = toPoint / d;
			    emittedFrom = direction;
			    float cosReceiver = dot(n, direction);
			    vec3 nRay = n;
			    if (thinSheet && cosReceiver < 0.0) {
			        cosReceiver = -cosReceiver;
			        nRay = -n;
			    }
			    vec3 lightNormal = cross(light.e1.xyz, light.e2.xyz);
			    float area = length(lightNormal);
			    float cosLight = abs(dot(lightNormal / area, direction));
			    float tMax = max(d - 0.03, 0.002);
			    if (cosReceiver <= 0.0 || cosLight <= 0.0) {
			        return vec3(0.0);
			    }
			    vec3 through = transmittance(p + nRay * 0.01, direction, tMax, 0x01u);
			    if (dot(through, vec3(1.0)) <= 0.0) {
			        return vec3(0.0);
			    }
			    shadowed = entitiesNear && occludedBy(p + nRay * 0.01, direction, tMax, 0x02u);
			    vec3 glow = pow(texel.rgb, vec3(2.2)) * emissive * 2.0;
			    return glow * through * window * cosReceiver * cosLight * area / max(d * d, 1.0 / 64.0) / probability
			            * switches.w / PI;
			}

			// Shadow samples for the share of the emitted light entities stop at p, traced by every pixel of a texel
			// where an entity shadow is about (TexelCache's sumA.w, sumB.w): SHADOW_SAMPLES of the cell's lights,
			// picked by how much each gives the point unshadowed (stratified, the cell's running totals as for
			// emissiveSample), a random point of each. x: how many
			// the terrain let through; y: how many of those an entity stopped.
			const uint SHADOW_SAMPLES = 4u;
			const float SHADOW_PIXELS = 64.0;
			// The cell's lights' running totals of weight (main's), read in place: an array argument cost the whole
			// stage 1.5 ms (copied, or held in registers, for every pixel).
			float weights[PER_CELL];
			uvec2 shadowSamples(vec3 p, vec3 g, vec3 n, bool thinSheet, uint count, uint indexBase, float total) {
			    if (total <= 0.0) {
			        return uvec2(0u);
			    }
			    uvec2 counts = uvec2(0u);
			    float offset = random();
			    for (uint k = 0u; k < SHADOW_SAMPLES; k++) {
			        float pick = (float(k) + offset) / float(SHADOW_SAMPLES) * total;
			        uint low = 0u;
			        uint high = count - 1u;
			        while (low < high) {
			            uint middle = (low + high) / 2u;
			            if (weights[middle] < pick) {
			                low = middle + 1u;
			            } else {
			                high = middle;
			            }
			        }
			        Light light = lights[cellWords[indexBase + low] & 0x7FFFFFFFu];
			        vec3 x = light.p0.xyz + light.e1.xyz * random() + light.e2.xyz * random();
			        vec3 toPoint = x - g;
			        float d = length(toPoint);
			        if (d < 0.05) {
			            continue;
			        }
			        vec3 direction = toPoint / d;
			        float cosReceiver = dot(n, direction);
			        vec3 nRay = thinSheet && cosReceiver < 0.0 ? -n : n;
			        if (cosReceiver <= 0.0 && !thinSheet) {
			            continue;
			        }
			        vec3 origin = p + nRay * 0.01;
			        float tMax = max(d - 0.03, 0.002);
			        if (occludedBy(origin, direction, tMax, 0x01u)) {
			            continue;
			        }
			        counts.x++;
			        if (occludedBy(origin, direction, tMax, 0x02u)) {
			            counts.y++;
			        }
			    }
			    return counts;
			}

			// One sample of the light bounced to point p (normal n): a cosine-weighted ray, and the light the surface
			// it meets sends back (in the cache's units: with a cosine-weighted pdf, the radiance itself).
			vec3 bounceSample(vec3 p, vec3 n, bool thinSheet, vec3 u) {
			    vec3 normal = n;
			    if (thinSheet && u.z < 0.5) {
			        normal = -n; // a thin sheet takes light from both sides
			    }
			    float r = sqrt(u.x);
			    float phi = u.y * 2.0 * PI;
			    vec3 side = normalize(cross(abs(normal.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), normal));
			    vec3 up = cross(normal, side);
			    vec3 direction = normalize(side * (r * cos(phi)) + up * (r * sin(phi)) + normal * sqrt(max(1.0 - r * r, 0.0)));
			    bouncedFrom = direction;
			    return bounceRadiance(p + normal * 0.01, direction);
			}

			// Whether a point x of an entity's light is seen from p: through the terrain all the way, and through the
			// entities short of the light (the one carrying it, with its hand and the item, isn't in the way).
			// through: the colour the stained glass on the way lets through.
			bool entityLightReaches(vec3 p, vec3 n, bool thinSheet, vec3 x, out vec3 through) {
			    through = vec3(1.0);
			    vec3 toPoint = x - p;
			    float d = length(toPoint);
			    if (d < 0.02) {
			        return true;
			    }
			    vec3 direction = toPoint / d;
			    vec3 nRay = thinSheet && dot(n, direction) < 0.0 ? -n : n;
			    vec3 origin = p + nRay * 0.01;
			    through = transmittance(origin, direction, max(d - 0.03, 0.002), 0x01u);
			    if (dot(through, vec3(1.0)) <= 0.0) {
			        return false;
			    }
			    float entityReach = d - 0.7;
			    return entityReach <= 0.0 || !occludedBy(origin, direction, entityReach, 0x02u);
			}

			void main() {
			    uvec2 pixel = gl_GlobalInvocationID.xy;
			    if (any(greaterThanEqual(pixel, size.xy))) {
			        return;
			    }
			    vec3 p;
			    vec3 n;
			    bool thinSheet;
			    int level;
			    if (!surface(pixel, p, n, thinSheet, level)) {
			        return;
			    }
			    int slot = findTexel(texelKey(p, n, thinSheet, originBlock.xyz, level), true);
			    if (slot < 0) {
			        return;
			    }
			    ivec3 block = ivec3(floor(p)) + originBlock.xyz;
			    // A texel whose history is new (just seen, or wiped by a change near it this frame) takes more samples
			    // for its first frames, so its light is right and clean at once: 12, then 6, 4, 3 and 2.
			    bool fresh = changeCount.x > 0 && changedNear(block);
			    float held = fresh ? 0.0 : entries[slot].historyA.w;
			    // Where an entity shadow is about (the texel's shares say so), every pixel adds shadow samples, the
			    // thinned ones too (shadowSamples).
			    bool shadowAbout = entityCount.y != 0 && max(entries[slot].fastC.x, entries[slot].fastC.z) > 0.02;
			    bool thinned = false;
			    // A texel near the camera covers many pixels, each of which traced its own samples into it: some 140
			    // at four blocks at 1080p. Each pixel now traces with the chance that gives its texel about
			    // TEXEL_SAMPLES a frame (all of them for texels a few pixels wide, and while a texel is new). Not in
			    // exports (camera.w 0 there).
			    if (held >= 5.0 && camera.w > 0.0) {
			        vec3 toCamera = camera.xyz - p;
			        float distance = max(length(toCamera), 0.5);
			        float side = camera.w * float(1 << level) / 16.0 / distance;
			        float pixels = side * side * max(abs(dot(n, toCamera / distance)), 0.2);
			        uint h = texelHash((pixel.y * size.x + pixel.x) ^ texelHash(size.z + 0x2545f491u));
			        float chance = float(h) / 4294967296.0 * pixels;
			        thinned = chance > TEXEL_SAMPLES;
			        // Shadow samples: from about SHADOW_PIXELS of the texel's pixels (some 250 samples a frame).
			        shadowAbout = shadowAbout && chance <= SHADOW_PIXELS;
			        if (thinned && !shadowAbout) {
			            return;
			        }
			    }
			    int samples = max(int(grid.w), 1);
			    if (held < 5.0) {
			        samples = max(samples, int(12.0 / (held + 1.0)));
			    }
			    state = (pixel.y * size.x + pixel.x) * 9781u + size.z * 6271u + 1u;
			    sequencePixel = pixel.y * size.x + pixel.x;

			    // How much each of the cell's lights would give the point, unshadowed, from its centre.
			    vec3 g = p + grid.xyz;
			    uint base = 0u;
			    uint indexBase = 0u;
			    // The cell is looked up up to half a cell away, at random for each 8 x 8 tile of pixels and frame (a tile
			    // reads one cell's list, as without it): a light in one cell's list but not the next one's fades out over
			    // a few blocks in the texels' averages, instead of stopping at the cells' boundary in a hard line.
			    uint tile = texelHash(gl_WorkGroupID.y * 4096u + gl_WorkGroupID.x ^ texelHash(size.z * 0x9e3779b9u));
			    vec3 cellJitter = (vec3(tile & 0x3FFu, (tile >> 10u) & 0x3FFu, (tile >> 20u) & 0x3FFu) / 1024.0 - 0.5) * CELL;
			    uint count = switches.x > 0.5 && gridCell(g + cellJitter, base, indexBase) ? floatBitsToUint(cells[base].x) : 0u;
			    float total = 0.0;
			    for (uint i = 0u; i < count; i++) {
			        vec4 entry = cells[base + 1u + i];
			        vec3 toLight = entry.xyz - g;
			        float d2 = max(dot(toLight, toLight), 0.25);
			        float facing = dot(n, toLight) * inversesqrt(d2);
			        facing = thinSheet ? abs(facing) : facing;
			        float w = entry.w * clamp(facing * 0.8 + 0.2, 0.0, 1.0) / d2 * reachWindow(sqrt(d2), reachOf(entry.w));
			        total += w;
			        weights[i] = total; // running total, for emissiveSample's pick
			    }
			    if (shadowAbout) {
			        uvec2 counts = shadowSamples(p, g, n, thinSheet, count, indexBase, total);
			        if (counts.x > 0u) {
			            atomicAdd(entries[slot].sumB.w, counts.x);
			            atomicAdd(entries[slot].sumA.w, counts.y);
			        }
			    }
			    if (thinned) {
			        return;
			    }
			    placeTexel(slot, block, level);
			    // The bounce also goes to the texel's bounce cell (TexelCache's bounceKey), which is what is shown.
			    int bounceCell = findTexel(bounceKey(p, n, thinSheet, originBlock.xyz, level), true);
			    if (bounceCell >= 0) {
			        placeTexel(bounceCell, block, bounceLevel(level));
			    }
			    vec3 bouncedSum = vec3(0.0);
			    entitiesNear = entityCount.y != 0;
			    // The lights entities carry: how much each gives the point, unshadowed. Their light itself is worked out
			    // again every frame where it is shown (GATHER); here only whether they are seen is sampled, a light
			    // picked by how much it gives, and counted into the share of them the texel sees.
			    uint carried = uint(entityCount.x);
			    float entityWeights[32];
			    float entityTotal = 0.0;
			    for (uint i = 0u; i < carried; i++) {
			        float w = dot(entityLight(i, p, n, thinSheet), vec3(1.0));
			        entityWeights[i] = w;
			        entityTotal += w;
			    }
			    for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
			        uint stream = uint(sampleIndex) * 2u;
			        bool shadowed;
			        vec3 emitted = emissiveSample(p, g, n, thinSheet, count, indexBase, weights, total, sequence3(stream),
			                shadowed);
			        // Half the samples trace the bounce, counted twice (soft light: as clean, 0.45 ms less at 1080p); all of
			        // them in exports.
			        bool bounces = switches.y > 0.5 && (camera.w <= 0.0 || random() < 0.5);
			        vec3 bounced = bounces ? bounceSample(p, n, thinSheet, sequence3(stream + 1u)) * (camera.w > 0.0 ? 2.0 : 1.0)
			                : vec3(0.0);
			        // The bounce goes to the texel too, for bounce rays meeting it (bounceRadiance: one lookup).
			        addSample(slot, emitted, bounced);
			        const vec3 LUMA3 = vec3(0.2126, 0.7152, 0.0722);
			        addDirection(slot, emittedFrom, dot(emitted, LUMA3));
			        if (bounces) {
			            addDirection(slot, bouncedFrom, dot(bounced, LUMA3));
			        }
			        bouncedSum += min(bounced, vec3(MAX_SAMPLE));
			        bool seen = false;
			        vec3 seenColor = vec3(1.0);
			        if (entityTotal > 0.0) {
			            vec3 u = sequence3(stream + 4096u);
			            float pick = u.x * entityTotal;
			            uint chosen = 0u;
			            for (uint i = 0u; i < carried; i++) {
			                chosen = i;
			                pick -= entityWeights[i];
			                if (pick <= 0.0) {
			                    break;
			                }
			            }
			            // A point of the light's sphere, on the half facing the point lit.
			            vec4 at = entityLights[chosen * 2u];
			            float z = u.y * 2.0 - 1.0;
			            float phi = u.z * 2.0 * PI;
			            vec3 offset = vec3(sqrt(max(1.0 - z * z, 0.0)) * vec2(cos(phi), sin(phi)), z);
			            vec3 towardsPoint = normalize(p - at.xyz);
			            offset = dot(offset, towardsPoint) < 0.0 ? reflect(offset, towardsPoint) : offset;
			            seen = entityLightReaches(p, n, thinSheet, at.xyz + offset * at.w, seenColor);
			        }
			        // Always, so a texel's share shadowed goes back to none once the last entity is gone.
			        addEntitySample(slot, dot(emitted, vec3(0.2126, 0.7152, 0.0722)), shadowed, entityTotal > 0.0, seen, seenColor);
			    }
			    if (bounceCell >= 0) {
			        addBounceSamples(bounceCell, bouncedSum, uint(samples));
			    }
			}
			""";

	/** Stage 2, a thread a cache entry: this frame's samples blended into its history. */
	private static final String BLEND = """
			#version 460
			#extension GL_EXT_ray_query : require
			#extension GL_EXT_buffer_reference : require
			#extension GL_EXT_buffer_reference_uvec2 : require
			#extension GL_EXT_scalar_block_layout : require
			layout(local_size_x = 64) in;
			#define OUTPUT_BINDING 10
			""" + COMMON + """
			void main() {
			    blendTexel(gl_GlobalInvocationID.x, size.z, float(originBlock.w));
			}
			""";

	/** Stage 3, a thread a pixel: its texel's light, to the output (emitted on the left, bounced on the right). */
	private static final String GATHER = GROUPS_8X8 + COMMON + """
			// Frames a texel's own light takes over from the coarser texel standing in for it.
			const float FILL = 8.0;
			void main() {
			    uvec2 pixel = gl_GlobalInvocationID.xy;
			    if (any(greaterThanEqual(pixel, size.xy))) {
			        return;
			    }
			    uint left = pixel.y * size.x * 3u + pixel.x;
			    uint right = left + size.x;
			    // A third section: where the texel's light comes from (TexelCache's E), for the highlight.
			    uint towards = right + size.x;
			    vec3 p;
			    vec3 n;
			    bool thinSheet;
			    int level;
			    if (!surface(pixel, p, n, thinSheet, level)) {
			        pixels[left] = uvec2(0u);
			        pixels[right] = uvec2(0u);
			        pixels[towards] = uvec2(0u);
			        return;
			    }
			    int slot = findTexel(texelKey(p, n, thinSheet, originBlock.xyz, level), false);
			    // The entity terms, which have only a few frames' samples: the texel's own, weighed twice, with its four
			    // neighbours' on the same face (those it has): shadowed share and carried lights seen.
			    vec2 entityTerms = vec2(0.0, 1.0);
			    // Only where an entity left a mark (a shadow, or a carried light partly hidden): elsewhere the
			    // neighbours' four lookups a pixel would only average nothing.
			    bool marked = slot >= 0 && (entries[slot].fastC.z > 0.001
			            || (entries[slot].fastC.y > 0.0 && entries[slot].fastC.y < 1.999));
			    if (slot >= 0 && !marked) {
			        entityTerms = vec2(entries[slot].fastC.z, entries[slot].fastC.y > 0.0 ? entries[slot].fastC.y - 1.0 : 1.0);
			    }
			    if (marked) {
			        float shadowSum = 2.0 * entries[slot].fastC.z;
			        float shadowWeight = 2.0;
			        float seenSum = entries[slot].fastC.y > 0.0 ? 2.0 * (entries[slot].fastC.y - 1.0) : 0.0;
			        float seenWeight = entries[slot].fastC.y > 0.0 ? 2.0 : 0.0;
			        vec3 a = abs(n);
			        if (!thinSheet && max(a.x, max(a.y, a.z)) >= 0.95) {
			            float size = float(1 << level) / 16.0;
			            vec3 axis = round(n);
			            vec3 u = abs(axis.x) > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
			            vec3 v = cross(axis, u);
			            // A shadow that moved lately (its slow share holds few frames: the fast one's noise) is averaged
			            // over twelve neighbours, out to two texels, instead of four.
			            vec3 steps[12] = vec3[12](u, -u, v, -v, u + v, u - v, v - u, -u - v, 2.0 * u, -2.0 * u, 2.0 * v,
			                    -2.0 * v);
			            int reach = entries[slot].fastC.w < 8.0 ? 12 : 4;
			            for (int i = 0; i < reach; i++) {
			                int near = findTexel(texelKey(p + steps[i] * size, n, false, originBlock.xyz, level), false);
			                if (near >= 0) {
			                    shadowSum += entries[near].fastC.z;
			                    shadowWeight += 1.0;
			                    if (i < 4 && entries[near].fastC.y > 0.0) {
			                        seenSum += entries[near].fastC.y - 1.0;
			                        seenWeight += 1.0;
			                    }
			                }
			            }
			        }
			        entityTerms = vec2(shadowSum / shadowWeight, seenWeight > 0.0 ? seenSum / seenWeight : 1.0);
			    }
			    // The emitted light as the terrain lets it through, less the share entities shadow lately.
			    vec3 emitted = slot >= 0 ? entries[slot].historyA.rgb * (1.0 - entityTerms.x) : vec3(0.0);
			    // The bounce from the bounce cells (TexelCache's bounceKey), blended between the four nearest on an
			    // axis-aligned face (bilinear, each weighed by the frames it holds, so a cell just seen counts little),
			    // else the one there.
			    vec3 bounced = vec3(0.0);
			    // How much of a settled history the bounce has here (0 to 1: FILL frames' worth). The lighting pass
			    // keeps Bedrock's sky ambient for the rest: a chunk just loaded, its cells empty, had no ambient light
			    // in its shade at all and showed dark, sharp shadows until the cache filled (slowly far off, where a
			    // texel covers few pixels).
			    float settled = 0.0;
			    vec3 axisN = abs(n);
			    if (!thinSheet && max(axisN.x, max(axisN.y, axisN.z)) >= 0.95) {
			        float cellSize = float(1 << bounceLevel(level)) / 16.0;
			        vec3 axis = round(n);
			        vec3 u = abs(axis.x) > 0.5 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
			        vec3 v = cross(axis, u);
			        vec2 at = vec2(dot(p, u), dot(p, v));
			        vec2 q = at / cellSize - 0.5;
			        vec2 low = floor(q);
			        vec2 f = q - low;
			        vec3 sum = vec3(0.0);
			        float weight = 0.0;
			        for (int i = 0; i < 4; i++) {
			            vec2 corner = vec2(float(i & 1), float(i >> 1));
			            vec2 centre = (low + corner + 0.5) * cellSize;
			            vec3 point = p + u * (centre.x - at.x) + v * (centre.y - at.y);
			            int cell = findTexel(bounceKey(point, n, false, originBlock.xyz, level), false);
			            if (cell >= 0) {
			                vec2 b = mix(1.0 - f, f, corner);
			                float w = b.x * b.y * min(entries[cell].historyA.w + 0.25, 8.0);
			                sum += entries[cell].historyB.rgb * w;
			                weight += w;
			            }
			        }
			        bounced = weight > 0.0 ? sum / weight : vec3(0.0);
			        settled = clamp(weight / FILL, 0.0, 1.0);
			    } else {
			        int cell = findTexel(bounceKey(p, n, thinSheet, originBlock.xyz, level), false);
			        bounced = cell >= 0 ? entries[cell].historyB.rgb : vec3(0.0);
			        settled = cell >= 0 ? clamp(entries[cell].historyA.w / FILL, 0.0, 1.0) : 0.0;
			    }
			    // A texel without light of its own yet (no entry, or fewer than FILL frames: just come into view, or
			    // nearer than before, at a finer level) shows the texel of the next coarser level over the same spot
			    // that has some (the one it was seen as from farther away, kept a while), and fades to its own over
			    // its first FILL frames; or, with none coarser, the next finer one (seen from nearer before).
			    float held = slot >= 0 ? entries[slot].historyA.w : 0.0;
			    if (held < FILL) {
			        int other = -1;
			        for (int up = level + 1; up <= 4 && other < 0; up++) {
			            vec3 centre = texelCentre(p, n, thinSheet, up);
			            int found = findTexel(texelKey(centre, n, thinSheet, originBlock.xyz, up), false);
			            other = found >= 0 && entries[found].historyA.w >= 2.0 ? found : -1;
			        }
			        if (other < 0 && level > 0) {
			            int found = findTexel(texelKey(p, n, thinSheet, originBlock.xyz, level - 1), false);
			            other = found >= 0 && entries[found].historyA.w >= 2.0 ? found : -1;
			        }
			        if (other >= 0) {
			            float own = held / FILL;
			            emitted = mix(entries[other].historyA.rgb * (1.0 - entries[other].fastC.z), emitted, own);
			        }
			    }
			    // The lights entities carry, from where they are this frame, times the share of them the texel saw
			    // lately (none measured yet: all of it).
			    uint carried = uint(entityCount.x);
			    if (carried > 0u) {
			        vec3 light = vec3(0.0);
			        for (uint i = 0u; i < carried; i++) {
			            light += entityLight(i, p, n, thinSheet);
			        }
			        // Tinted by the stained glass the rays to them crossed (TexelCache's D).
			        vec3 tint = slot >= 0 && entries[slot].fastD.w > 0.0 ? entries[slot].fastD.rgb : vec3(1.0);
			        emitted += light * entityTerms.y * tint;
			    }

			    // Alpha: 0. Points on blocks that glow all over (lava) are found by the lighting pass from the light
			    // volume's flag (EmitterColors.glowsAllOver); this pass's own search of the cell's lights for them cost
			    // a walk through up to PER_CELL lights a pixel.
			    pixels[left] = uvec2(packHalf2x16(emitted.rg), packHalf2x16(vec2(emitted.b, 0.0)));
			    pixels[right] = uvec2(packHalf2x16(bounced.rg), packHalf2x16(vec2(bounced.b, settled)));
			    vec3 from = slot >= 0 ? entries[slot].fastE.xyz : vec3(0.0);
			    pixels[towards] = uvec2(packHalf2x16(from.xy), packHalf2x16(vec2(from.z, 0.0)));
			}
			""";

	private static final FrameCompute PASS = new FrameCompute("ray-traced diffuse light",
			new String[] {TRACE, BLEND, GATHER}, new int[] {
			VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
			VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
			VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
			VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
			VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, 128, 8, GpuFormat.RGBA16_FLOAT);

	private static Mode mode = Mode.OFF;
	private static boolean bounce;
	/** Bounce rays that reach the sky bring its light back, in place of Bedrock's sky ambient term (off: Bedrock's). */
	private static boolean skyBounce;
	/**
	 * Scales the emitted light (1: the emissive texels' glow as the lighting pass draws them). 10 matches Bedrock's
	 * block light a block or two from glowstone (test scene, same exposure); larger glowing faces (sea lanterns,
	 * shroomlights) come out brighter than Bedrock's and small ones (torches) dimmer.
	 */
	private static float gain = 10.0f;
	/** Scales the bounced light (1: physical, for the surfaces' colours as the block atlas has them). */
	private static float bounceGain = 1.0f;
	/** In {@link Mode#FLOOR}: the share of Bedrock's block light kept as the least light anywhere. */
	private static float floor = 0.35f;
	private static int samples = 1;
	/** Samples a pixel in each of the offline preset's sub-frames. */
	private static int offlineSamples = 16;
	private static boolean entityLights = true;
	/**
	 * How long a texel's slow average reaches back while playing (TexelCache), in seconds: as many frames as the
	 * game draws in that time, between {@link #MIN_HISTORY} and {@link #MAX_HISTORY}.
	 */
	private static final float HISTORY_SECONDS = 1.0f;
	private static final int MIN_HISTORY = 16;
	private static final int MAX_HISTORY = 96;
	/** Places the world changed since the last dispatch (TexelCache), at most {@link #MAX_CHANGES}. */
	private static final int MAX_CHANGES = 16;
	/**
	 * How far a change reaches, in blocks: a block placed or broken changes the light and shadows of the texels
	 * around it, as far as a light reaches (EmissiveLights' reach is up to 32 blocks; most are under 20).
	 */
	private static final int CHANGE_REACH = 24;
	/**
	 * Per change: its box (lowest, highest world block) and how many more dispatches it wipes texels for. Two:
	 * in the first frame after a change the bounce rays still read their neighbours' light from before it, and a
	 * second wipe drops that frame from the history instead of letting it fade out over the next second.
	 */
	private static final java.util.List<int[]> CHANGES = new java.util.ArrayList<>();
	private static final int CHANGE_DISPATCHES = 2;
	/** Where the frame data's entity lights start: after the light, the switches and the changes. */
	private static final int ENTITY_LIGHTS_AT = 64 + MAX_CHANGES * 32;
	/** The frame data's skyBounceLight, after the entity lights. */
	private static final int SKY_AT = ENTITY_LIGHTS_AT + 16 + EntityLights.MAX * EntityLights.BYTES;
	/**
	 * In exports, in sub-frames: 64 is 8 output frames at 8 sub-frames each, which is as far back as a change
	 * too small to set off the history's reaction (TexelCache) can trail.
	 */
	private static final int EXPORT_HISTORY = 64;
	private static boolean failed;
	private static String failure = "";
	private static boolean tracedThisFrame;
	private static long frameNumber;

	private RtEmissive() {
	}

	/**
	 * A section of the world (its origin, a multiple of 16) changed: a block placed or broken, its light. The cached
	 * light of every texel near it starts over from the next frame (TexelCache).
	 */
	static void changed(int originX, int originY, int originZ) {
		int[] box = {originX - CHANGE_REACH, originY - CHANGE_REACH, originZ - CHANGE_REACH, originX + 15 + CHANGE_REACH,
				originY + 15 + CHANGE_REACH, originZ + 15 + CHANGE_REACH, CHANGE_DISPATCHES};
		if (CHANGES.size() < MAX_CHANGES) {
			CHANGES.add(box);
		} else {
			// More than fit: the last one grows to hold them.
			int[] last = CHANGES.getLast();
			for (int i = 0; i < 3; i++) {
				last[i] = Math.min(last[i], box[i]);
				last[i + 3] = Math.max(last[i + 3], box[i + 3]);
			}
			last[6] = CHANGE_DISPATCHES;
		}
	}

	private static int history(boolean exporting) {
		if (exporting) {
			return EXPORT_HISTORY;
		}
		return Math.clamp(Math.round(net.minecraft.client.Minecraft.getInstance().getFps() * HISTORY_SECONDS),
				MIN_HISTORY, MAX_HISTORY);
	}

	public static Mode mode() {
		return mode;
	}

	public static void setMode(Mode newMode) {
		mode = newMode;
		failed = false;
		if (newMode != Mode.OFF) {
			RtScene.setEnabled(true);
		}
	}

	/** Turns the bounce light (sun, moon and emitted light off other surfaces) on or off. */
	public static void setBounce(boolean on) {
		bounce = on;
		failed = false;
		if (on) {
			RtScene.setEnabled(true);
		}
	}

	public static boolean bounce() {
		return bounce;
	}

	public static float gain() {
		return gain;
	}

	public static float bounceGain() {
		return bounceGain;
	}

	public static float floor() {
		return floor;
	}

	public static int samples() {
		return samples;
	}

	public static int offlineSamples() {
		return offlineSamples;
	}

	/** Whether entities carrying or giving off light (EntityLights) light the world too. */
	public static boolean entityLights() {
		return entityLights;
	}

	public static void setEntityLights(boolean on) {
		entityLights = on;
	}

	public static void setGain(float value) {
		gain = Math.max(value, 0);
	}

	public static void setBounceGain(float value) {
		bounceGain = Math.max(value, 0);
	}

	public static void setFloor(float value) {
		floor = Math.clamp(value, 0, 1);
	}

	/** Samples a pixel a frame while playing. */
	public static void setSamples(int value) {
		samples = Math.clamp(value, 1, 16);
	}

	/** Samples a pixel in each sub-frame of the offline preset (exports). */
	public static void setOfflineSamples(int value) {
		offlineSamples = Math.clamp(value, 1, 16);
	}

	/** Whether the lighting pass reads the emitted light from {@link #view()} this frame. */
	public static boolean active() {
		return tracedThisFrame && mode != Mode.OFF;
	}

	/** Whether the lighting pass adds the bounced light from {@link #view()} this frame. */
	public static void setSkyBounce(boolean on) {
		skyBounce = on;
		BedrockVisualsClient.LOGGER.info("[raytrace] sky light bounced: {}", on);
	}

	public static boolean skyBounce() {
		return skyBounce;
	}

	/** Whether this frame's bounce brings the sky's light back (the lighting then leaves out Bedrock's sky term). */
	public static boolean skyBounceActive() {
		return skyBounce && bounceActive();
	}

	public static boolean bounceActive() {
		return tracedThisFrame && bounce;
	}

	/** The share of Bedrock's block light the lighting pass keeps under the traced light (1 in place of it: 0). */
	public static float floorShare() {
		return mode == Mode.FLOOR ? floor : 0;
	}

	public static GpuTextureView view() {
		return PASS.view();
	}

	/**
	 * Traces this frame's diffuse light, after the G-buffer is drawn and before the lighting pass reads it.
	 *
	 * @param towardsLight, lightColor the light that casts this frame's shadows (the sun, or the moon at night),
	 *        as the lighting pass has it
	 */
	public static void trace(GpuTextureView depth, GpuTextureView normal, GpuTextureView atlas, Matrix4fc projection,
			Matrix4fc modelView, Vector3fc towardsLight, Vector3fc lightColor) {
		tracedThisFrame = false;
		EntityProbes.skip();
		if ((mode == Mode.OFF && !bounce) || failed) {
			return;
		}
		GpuTextureView materials = MaterialAtlas.currentView();
		RtScene.Frame frame = RtScene.ensureBuilt();
		if (frame == null || materials == null) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			RtDevice rt = RtDevice.get();
			EmissiveLights.Grid grid = EmissiveLights.grid(rt, frame.cameraX(), frame.cameraY(), frame.cameraZ());
			int w = depth.getWidth(0);
			int h = depth.getHeight(0);
			boolean exporting = dev.bedrockvisuals.render.OfflineRender.active();
			Matrix4f inverse = new Matrix4f(projection).mul(modelView).invert();
			ByteBuffer constants = stack.malloc(128);
			inverse.get(0, constants);
			constants.putFloat(64, (float) (frame.cameraX() - frame.originX()));
			constants.putFloat(68, (float) (frame.cameraY() - frame.originY()));
			constants.putFloat(72, (float) (frame.cameraZ() - frame.originZ()));
			constants.putFloat(76, exporting ? 0 : Math.abs(projection.m11()) * h * 0.5f);
			constants.putFloat(80, frame.originX() - grid.minX()).putFloat(84, frame.originY() - grid.minY())
					.putFloat(88, frame.originZ() - grid.minZ()).putFloat(92, exporting ? offlineSamples : samples);
			constants.putInt(96, w).putInt(100, h).putInt(104, (int) frameNumber++).putInt(108, 0);
			constants.putInt(112, frame.originX()).putInt(116, frame.originY()).putInt(120, frame.originZ())
					.putInt(124, history(exporting));

			// This frame's light and switches, freed once the frame has run.
			RtBuffer params = rt.hostBuffer(SKY_AT + 16,
					VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
			rt.free(params);
			ByteBuffer frameData = params.bytes();
			frameData.putFloat(0, towardsLight.x()).putFloat(4, towardsLight.y()).putFloat(8, towardsLight.z())
					.putFloat(12, 0).putFloat(16, lightColor.x()).putFloat(20, lightColor.y())
					.putFloat(24, lightColor.z()).putFloat(28, 0).putFloat(32, mode != Mode.OFF ? 1 : 0)
					.putFloat(36, bounce ? 1 : 0).putFloat(40, bounceGain).putFloat(44, gain);
			// The places the world changed since the last dispatch: the texels near them start over.
			frameData.putInt(48, CHANGES.size());
			for (int i = 0; i < CHANGES.size(); i++) {
				int[] box = CHANGES.get(i);
				int at = 64 + i * 32;
				frameData.putInt(at, box[0]).putInt(at + 4, box[1]).putInt(at + 8, box[2]).putInt(at + 12, 0)
						.putInt(at + 16, box[3]).putInt(at + 20, box[4]).putInt(at + 24, box[5]).putInt(at + 28, 0);
			}
			CHANGES.removeIf(box -> --box[6] <= 0);
			// The lights entities carry (EntityLights), with the emitted light.
			int carried = 0;
			if (mode != Mode.OFF && entityLights) {
				carried = EntityLights.write(EntityLights.collect(frame.cameraX(), frame.cameraY(), frame.cameraZ()),
						frameData, ENTITY_LIGHTS_AT + 16, frame.originX(), frame.originY(), frame.originZ());
			}
			com.mojang.renderpearl.api.textures.GpuTextureView lightVolume = dev.bedrockvisuals.render.LightVolume.view();
			org.joml.Vector3f skyLight = dev.bedrockvisuals.render.LightingState.current().sky();
			frameData.putFloat(SKY_AT, skyLight.x()).putFloat(SKY_AT + 4, skyLight.y()).putFloat(SKY_AT + 8, skyLight.z())
					.putFloat(SKY_AT + 12, skyBounce && bounce ? 1 : 0);
			frameData.putInt(ENTITY_LIGHTS_AT, carried).putInt(ENTITY_LIGHTS_AT + 4, EntityGeometry.quads() > 0 ? 1 : 0)
					.putInt(ENTITY_LIGHTS_AT + 8, lightVolume != null ? 1 : 0);

			List<FrameCompute.Binding> bindings = List.of(new FrameCompute.Structure(frame.tlas()),
					new FrameCompute.Buffer(frame.instances().buffer()), new FrameCompute.Image(depth),
					new FrameCompute.Image(normal), new FrameCompute.Image(atlas), new FrameCompute.Image(materials),
					new FrameCompute.Buffer(grid.lights().buffer()), new FrameCompute.Buffer(grid.cells().buffer()),
					new FrameCompute.Buffer(TexelCache.buffer(rt).buffer()), new FrameCompute.Buffer(params.buffer()));
			PASS.dispatch(rt, w * 3, h, bindings, constants, new int[][] {{(w + 7) / 8, (h + 7) / 8, 1},
					{TexelCache.ENTRIES / 64, 1, 1}, {(w + 7) / 8, (h + 7) / 8, 1}});
			tracedThisFrame = true;
			// The entities' light (EntityProbes): the emitted light and the bounce, whichever is on.
			EntityProbes.dispatch(rt, bindings, constants, lightVolume, materials, frame);
		} catch (RuntimeException e) {
			failed = true;
			failure = e.getMessage();
			BedrockVisualsClient.LOGGER.error("[raytrace] ray-traced diffuse light failed; back to Bedrock's", e);
		}
	}

	/** A line for the status report. */
	public static String status() {
		if (failed) {
			return "FAILED (" + failure + ")";
		}
		if (mode == Mode.OFF && !bounce) {
			return "off (Bedrock's block light, no bounce)";
		}
		return String.format(Locale.ROOT, "emitted %s (gain %.2f, floor %.2f), bounce %s (gain %.2f); %d samples "
						+ "(%d a sub-frame in exports); %s; %s this frame", mode.name().toLowerCase(Locale.ROOT), gain,
				floor, bounce ? "on" : "off", bounceGain, samples, offlineSamples, EmissiveLights.status(),
				tracedThisFrame ? "traced" : "not traced");
	}
}
