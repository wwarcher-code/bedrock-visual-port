package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Ray-traced diffuse light per texel of the world's surfaces, kept across frames: the per-texel quantized
 * lighting of the design (docs/ray-tracing-notes.md). Every pixel showing a texel adds its samples to that
 * texel's entry, a pass blends each entry's new samples into its history, and the pixels read the history back.
 * A texel's light so averages all its pixels' samples, over many frames, instead of each pixel showing its own
 * few: no grain, and no blur across texels either.
 *
 * <p>Two kinds of light are kept apart, as the lighting pass uses them differently: A, the light from emissive
 * texels (in place of Bedrock's block light), and B, light bounced off other surfaces (added to Bedrock's
 * ambient terms). Both are in the units of Bedrock's indirect light (light arriving over pi: the lighting pass
 * multiplies them by the diffuse colour).
 *
 * <p>A hash table on the GPU of {@link #ENTRIES} entries, 112 bytes each (std430):
 * <pre>
 *   uint key        the texel's check hash (0: empty); claimed with an atomic compare-and-swap
 *   uint lastFrame  the frame its samples last arrived (entries left unseen for EVICT frames are freed)
 *   uint count      this frame's samples so far
 *   uint pad
 *   uvec4 sumA      this frame's samples of A, rgb in 1/1024 fixed point (atomic adds)
 *   uvec4 sumB      of B
 *   vec4 historyA   the slow average of A (rgb), shown, and how many frames it holds (w, capped)
 *   vec4 historyB   the slow average of B (rgb), and how much a frame's luminance varies (w: its variance)
 *   vec4 fastA      the fast average of A (rgb), over the last FAST frames, and how many it holds (w)
 *   vec4 fastB      the fast average of B (rgb)
 * </pre>
 * The slow average hides the noise; the fast one notices real changes (a light placed or broken, a new shadow)
 * and the slow one jumps to it then (see blendTexel).
 * A texel is its world texel coordinates (in the face's plane: the texel; across it: the plane's step) at its
 * level, and its face's direction; crossed plants, with no axis-aligned face, by their cell. Level 0 is Bedrock's
 * 1/16-block texel; farther from the camera, where texels are smaller than a pixel, levels 1 to 4 halve the
 * resolution each (8, 4, 2, then 1 a block), so the table stays small and far surfaces gather samples faster. Up
 * to PROBES slots from the hash are tried.
 *
 * <p>Only the G-buffer's terrain is cached (entities are lit forward), so texels don't move.
 */
final class TexelCache {
	static final int ENTRIES = 1 << 21;
	static final int ENTRY_SIZE = 112;

	/** The GLSL for a shader with the cache at binding {@code CACHE_BINDING} (define it first). */
	static final String GLSL = """
			struct TexelEntry { uint key; uint lastFrame; uint count; uint pad; uvec4 sumA; uvec4 sumB;
			    vec4 historyA; vec4 historyB; vec4 fastA; vec4 fastB; };
			layout(set = 0, binding = CACHE_BINDING, std430) buffer TexelCache { TexelEntry entries[]; };
			const uint CACHE_ENTRIES = ENTRIES_HEREu;
			const uint PROBES = 8u;
			const float FIXED_POINT = 1024.0;
			// A single sample's cap: rare very bright samples (a light picked with a small probability, seen close)
			// would otherwise flash a texel for a while. A little light near the brightest lights is lost.
			const float MAX_SAMPLE = 64.0;
			const uint EVICT = 600u;

			uint texelHash(uint x) {
			    x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u; x *= 0x846ca68bu; x ^= x >> 16u;
			    return x;
			}

			// The texel level for a point this far from the camera (blocks).
			int texelLevel(float distance) {
			    return clamp(int(floor(log2(max(distance, 1.0) / 32.0))) + 1, 0, 4);
			}

			// p (relative to the scene's origin) moved to the centre of its texel at that level, within its face's
			// plane; crossed plants (thinSheet) stay where they are.
			vec3 texelCentre(vec3 p, vec3 n, bool thinSheet, int level) {
			    vec3 a = abs(n);
			    if (thinSheet || max(a.x, max(a.y, a.z)) < 0.95) {
			        return p;
			    }
			    float size = float(1 << level) / 16.0;
			    vec3 axis = round(n);
			    vec3 toCentre = size * 0.5 - (p - floor(p / size) * size);
			    return p + toCentre - dot(toCentre, axis) * axis;
			}

			// The texel at point p (relative to the scene's origin, a whole block) with normal n at a level: its slot
			// hash (x) and check (y, never 0). originBlock: the scene's origin in world blocks.
			uvec2 texelKey(vec3 p, vec3 n, bool thinSheet, ivec3 originBlock, int level) {
			    float perBlock = float(16 >> level);
			    vec3 at = p * perBlock;
			    ivec3 texel = ivec3(floor(at));
			    uint face = 6u;
			    vec3 a = abs(n);
			    if (!thinSheet && max(a.x, max(a.y, a.z)) >= 0.95) {
			        int axis = a.x >= a.y && a.x >= a.z ? 0 : a.y >= a.z ? 1 : 2;
			        texel[axis] = int(round(at[axis]));
			        face = uint(axis * 2) + (n[axis] > 0.0 ? 1u : 0u);
			    }
			    uvec3 world = uvec3(texel + originBlock * (16 >> level));
			    uint h = texelHash(world.x ^ texelHash(world.y ^ texelHash(world.z ^ texelHash(face + uint(level) * 8u
			            + 0x9e3779b9u))));
			    return uvec2(h, texelHash(h ^ 0x85ebca6bu) | 1u);
			}

			// The entry of a texel, claiming a free one if asked; -1 if there is none.
			int findTexel(uvec2 key, bool claim) {
			    for (uint i = 0u; i < PROBES; i++) {
			        uint slot = (key.x + i) % CACHE_ENTRIES;
			        if (entries[slot].key == key.y) {
			            return int(slot);
			        }
			    }
			    if (claim) {
			        for (uint i = 0u; i < PROBES; i++) {
			            uint slot = (key.x + i) % CACHE_ENTRIES;
			            uint previous = atomicCompSwap(entries[slot].key, 0u, key.y);
			            if (previous == 0u || previous == key.y) {
			                return int(slot);
			            }
			        }
			    }
			    return -1;
			}

			void addSample(int slot, vec3 a, vec3 b) {
			    uvec3 fixedA = uvec3(clamp(a, 0.0, MAX_SAMPLE) * FIXED_POINT + 0.5);
			    uvec3 fixedB = uvec3(clamp(b, 0.0, MAX_SAMPLE) * FIXED_POINT + 0.5);
			    atomicAdd(entries[slot].sumA.x, fixedA.x);
			    atomicAdd(entries[slot].sumA.y, fixedA.y);
			    atomicAdd(entries[slot].sumA.z, fixedA.z);
			    atomicAdd(entries[slot].sumB.x, fixedB.x);
			    atomicAdd(entries[slot].sumB.y, fixedB.y);
			    atomicAdd(entries[slot].sumB.z, fixedB.z);
			    atomicAdd(entries[slot].count, 1u);
			}

			// The blend stage, one thread an entry: this frame's mean into the averages, and entries unseen for EVICT
			// frames freed. Each frame weighs the same whatever its sample count (a texel near the camera, many
			// pixels wide, gets many samples a frame; weighing them by count let every frame outweigh the history,
			// each texel's light re-rolled every frame: static).
			//
			// Two averages: a slow one (up to cap frames) that hides the noise and is shown, and a fast one (FAST
			// frames) that follows changes. A single frame is too noisy to tell a change from noise; the fast
			// average isn't. When it is further from the slow one than its own noise explains (4 standard
			// deviations of a FAST-frame average, from the frames' variance, and a twentieth of the light), the
			// light really changed: the slow average takes the fast one's value and count, and the change shows in a
			// few frames instead of the whole slow history.
			const float FAST = 6.0;
			void blendTexel(uint slot, uint frame, float cap) {
			    if (slot >= CACHE_ENTRIES || entries[slot].key == 0u) {
			        return;
			    }
			    uint count = entries[slot].count;
			    if (count > 0u) {
			        const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
			        vec3 meanA = vec3(entries[slot].sumA.xyz) / FIXED_POINT / float(count);
			        vec3 meanB = vec3(entries[slot].sumB.xyz) / FIXED_POINT / float(count);
			        float now = dot(meanA + meanB, LUMA);

			        vec4 fastA = entries[slot].fastA;
			        vec4 fastB = entries[slot].fastB;
			        float fastWeight = 1.0 / (fastA.w + 1.0);
			        // The frames' variance around the fast average (before this frame joins it).
			        float before = dot(fastA.rgb + fastB.rgb, LUMA);
			        float variance = fastA.w > 0.0
			                ? mix(entries[slot].historyB.w, (now - before) * (now - before), 1.0 / 16.0) : 0.0;
			        fastA = vec4(mix(fastA.rgb, meanA, fastWeight), min(fastA.w + 1.0, FAST));
			        fastB = vec4(mix(fastB.rgb, meanB, fastWeight), 0.0);

			        // Held to this frame's cap first: a lowered cap (the world changed) takes effect at once.
			        float held = min(entries[slot].historyA.w, cap);
			        float slowWeight = 1.0 / (held + 1.0);
			        vec3 slowA = mix(entries[slot].historyA.rgb, meanA, slowWeight);
			        vec3 slowB = mix(entries[slot].historyB.rgb, meanB, slowWeight);
			        held = min(held + 1.0, cap);

			        float fastLight = dot(fastA.rgb + fastB.rgb, LUMA);
			        float slowLight = dot(slowA + slowB, LUMA);
			        // An exponential average of weight w over noise of variance v varies by v * w / (2 - w).
			        float fastNoise = sqrt(variance * fastWeight / (2.0 - fastWeight));
			        if (fastA.w >= FAST && abs(fastLight - slowLight) > 4.0 * fastNoise + 0.05 * slowLight + 0.001) {
			            slowA = fastA.rgb;
			            slowB = fastB.rgb;
			            held = fastA.w;
			        }
			        entries[slot].historyA = vec4(slowA, held);
			        entries[slot].historyB = vec4(slowB, variance);
			        entries[slot].fastA = fastA;
			        entries[slot].fastB = fastB;
			        entries[slot].sumA = uvec4(0u);
			        entries[slot].sumB = uvec4(0u);
			        entries[slot].count = 0u;
			        entries[slot].lastFrame = frame;
			    } else if (frame - entries[slot].lastFrame > EVICT) {
			        entries[slot].historyA = vec4(0.0);
			        entries[slot].historyB = vec4(0.0);
			        entries[slot].fastA = vec4(0.0);
			        entries[slot].fastB = vec4(0.0);
			        entries[slot].key = 0u;
			    }
			}
			""".replace("ENTRIES_HERE", Integer.toString(ENTRIES));

	private static RtBuffer buffer;

	private TexelCache() {
	}

	/** The cache's buffer, made (and zeroed) on first use. */
	static RtBuffer buffer(RtDevice rt) {
		if (buffer == null) {
			buffer = rt.deviceBuffer((long) ENTRIES * ENTRY_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
					| VK_BUFFER_USAGE_TRANSFER_DST_BIT);
			long serial = rt.submit(commands -> vkCmdFillBuffer(commands, buffer.buffer(), 0, VK_WHOLE_SIZE, 0));
			rt.waitFor(serial);
		}
		return buffer;
	}

	/** Forgets every texel (the world changed). */
	static void clear() {
		RtDevice rt = RtDevice.get();
		if (buffer != null && rt != null) {
			rt.free(buffer);
		}
		buffer = null;
	}
}
