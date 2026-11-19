package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Ray-traced diffuse light per 1/16-block texel of the world's surfaces, kept across frames: the per-texel
 * quantized lighting of the design (docs/ray-tracing-notes.md). Every pixel showing a texel adds its samples to
 * that texel's entry, a pass blends each entry's new samples into its history, and the pixels read the
 * history back. A texel's light so averages all its pixels' samples, over many frames, instead of each pixel
 * showing its own few: no grain, and no blur across texels either.
 *
 * <p>A hash table on the GPU of {@link #ENTRIES} entries, 48 bytes each (std430):
 * <pre>
 *   uint key        the texel's check hash (0: empty); claimed with an atomic compare-and-swap
 *   uint lastFrame  the frame its samples last arrived (entries left unseen for {@code EVICT} frames are freed)
 *   uint count      this frame's samples so far
 *   uint pad
 *   uvec4 sum       this frame's samples' sum, rgb in 1/1024 fixed point (atomic adds), w unused
 *   vec4 history    the averaged light (rgb) and how many samples it holds (w), capped so it still changes
 * </pre>
 * A texel is its world texel coordinates (in the face's plane: the texel; across it: the plane's 1/16 step) and
 * its face's direction; crossed plants, with no axis-aligned face, by their 1/16 cell. Up to {@code PROBES}
 * slots from the hash are tried.
 *
 * <p>Only the G-buffer's terrain is cached (entities are lit forward), so texels don't move.
 */
final class TexelCache {
	static final int ENTRIES = 1 << 21;
	static final int ENTRY_SIZE = 48;

	/** The GLSL for a shader with the cache at binding {@code CACHE_BINDING} (define it first). */
	static final String GLSL = """
			struct TexelEntry { uint key; uint lastFrame; uint count; uint pad; uvec4 sum; vec4 history; };
			layout(set = 0, binding = CACHE_BINDING, std430) buffer TexelCache { TexelEntry entries[]; };
			const uint CACHE_ENTRIES = ENTRIES_HEREu;
			const uint PROBES = 8u;
			const float FIXED_POINT = 1024.0;
			const float MAX_SAMPLE = 2048.0;
			const uint EVICT = 600u;

			uint texelHash(uint x) {
			    x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u; x *= 0x846ca68bu; x ^= x >> 16u;
			    return x;
			}

			// The texel at point p (relative to the scene's origin, a whole block) with normal n: its slot hash (x)
			// and check (y, never 0). originBlock: the scene's origin in world blocks.
			uvec2 texelKey(vec3 p, vec3 n, bool thinSheet, ivec3 originBlock) {
			    vec3 at = p * 16.0;
			    ivec3 texel = ivec3(floor(at));
			    uint face = 6u;
			    vec3 a = abs(n);
			    if (!thinSheet && max(a.x, max(a.y, a.z)) >= 0.95) {
			        int axis = a.x >= a.y && a.x >= a.z ? 0 : a.y >= a.z ? 1 : 2;
			        texel[axis] = int(round(at[axis]));
			        face = uint(axis * 2) + (n[axis] > 0.0 ? 1u : 0u);
			    }
			    uvec3 world = uvec3(texel + originBlock * 16);
			    uint h = texelHash(world.x ^ texelHash(world.y ^ texelHash(world.z ^ texelHash(face + 0x9e3779b9u))));
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

			void addSample(int slot, vec3 light) {
			    uvec3 fixedLight = uvec3(clamp(light, 0.0, MAX_SAMPLE) * FIXED_POINT + 0.5);
			    atomicAdd(entries[slot].sum.x, fixedLight.x);
			    atomicAdd(entries[slot].sum.y, fixedLight.y);
			    atomicAdd(entries[slot].sum.z, fixedLight.z);
			    atomicAdd(entries[slot].count, 1u);
			}

			// The blend stage, one thread an entry: this frame's samples into the history (at most cap samples
			// held), and entries unseen for EVICT frames freed.
			void blendTexel(uint slot, uint frame, float cap) {
			    if (slot >= CACHE_ENTRIES || entries[slot].key == 0u) {
			        return;
			    }
			    uint count = entries[slot].count;
			    if (count > 0u) {
			        vec3 mean = vec3(entries[slot].sum.xyz) / FIXED_POINT / float(count);
			        vec4 history = entries[slot].history;
			        float total = history.w + float(count);
			        entries[slot].history = vec4((history.rgb * history.w + mean * float(count)) / total, min(total, cap));
			        entries[slot].sum = uvec4(0u);
			        entries[slot].count = 0u;
			        entries[slot].lastFrame = frame;
			    } else if (frame - entries[slot].lastFrame > EVICT) {
			        entries[slot].history = vec4(0.0);
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
