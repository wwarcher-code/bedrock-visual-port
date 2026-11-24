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
 * <p>A hash table on the GPU of {@link #ENTRIES} entries, 160 bytes each (std430):
 * <pre>
 *   uint key        the texel's check hash (0: empty); claimed with an atomic compare-and-swap
 *   uint lastFrame  the frame its samples last arrived (entries left unseen for EVICT frames are freed)
 *   uint count      this frame's samples so far
 *   uint wiped      the frame its history was last wiped by a change near it
 *   uvec4 sumA      this frame's samples of A, rgb in 1/1024 fixed point (atomic adds); w: of this frame's
 *                   shadow samples that reached the terrain's light, how many an entity stopped
 *   uvec4 sumB      of B; w: how many shadow samples reached the terrain's light
 *   uvec4 sumC      this frame's entity samples (fixed point): x the emitted light entities stood in front of,
 *                   y all the emitted light the terrain let through; z how many shadow rays reached the lights
 *                   entities carry (EntityLights), w how many were traced
 *   vec4 historyA   the slow average of A (rgb), shown, and how many frames it holds (w, capped)
 *   vec4 historyB   the slow average of B (rgb), and how much a frame's luminance varies (w: its variance)
 *   vec4 fastA      the fast average of A (rgb), over the last FAST frames, and how many it holds (w)
 *   vec4 fastB      the fast average of B (rgb)
 *   vec4 fastC      over the last couple of frames: x the share of A that entities shadow, y the share of the
 *                   entities' lights the texel sees (plus one; 0 before any was measured); z the slow average
 *                   of x's share, shown, and how many frames it holds (w)
 *   ivec4 position  the texel's world block (xyz) and level (w), for wiping it when the world changes near it
 *   uvec4 sumD      this frame's carried-light rays that got through: their colour (stained glass; xyz), how many
 *   vec4 fastD      its average over the last couple of frames (rgb; w 1 once measured)
 *   ivec4 sumE      this frame's samples' directions weighed by their light (xyz, 1/256 fixed point), the weights
 *   vec4 fastE      their mean over up to 16 frames (xyz: towards the light, as long as it comes from one way),
 *                   frames held (w)
 * </pre>
 * The slow average hides the noise; the fast one notices changes the world doesn't report (see blendTexel). When
 * the world reports one (a section built again: a block placed or broken), every texel near it, on screen or not,
 * has its history wiped and starts over from the next frame's light: the change shows at once, a little noisy for
 * the first frames.
 *
 * <p>The shader including this must declare, before it, {@code changeCount} (x: how many) and {@code changes[]}
 * (per change, the lowest and the highest world block it reaches, as two ivec4s).
 * A texel is its world texel coordinates (in the face's plane: the texel; across it: the plane's step) at its
 * level, and its face's direction; crossed plants, with no axis-aligned face, by their cell. Level 0 is Bedrock's
 * 1/16-block texel; farther from the camera, where texels are smaller than a pixel, levels 1 to 4 halve the
 * resolution each (8, 4, 2, then 1 a block), so the table stays small and far surfaces gather samples faster. Up
 * to PROBES slots from the hash are tried.
 *
 * <p>Only the G-buffer's terrain is cached (entities are lit forward), so texels don't move. Entities do: what they
 * change is kept apart from the averages A and B, which see the terrain alone, and followed within a couple of
 * frames (C): their shadows over the emitted light, as a share of it, and how much of the lights they carry each
 * texel sees. A mob walking past a lantern so leaves no trail of shadow, and a torch carried by moves its light
 * with it.
 */
final class TexelCache {
	static final int ENTRIES = 1 << 21;
	static final int ENTRY_SIZE = 224;

	/** The GLSL for a shader with the cache at binding {@code CACHE_BINDING} (define it first). */
	static final String GLSL = """
			struct TexelEntry { uint key; uint lastFrame; uint count; uint wiped; uvec4 sumA; uvec4 sumB; uvec4 sumC;
			    vec4 historyA; vec4 historyB; vec4 fastA; vec4 fastB; vec4 fastC; ivec4 position; uvec4 sumD; vec4 fastD; ivec4 sumE; vec4 fastE; };
			layout(set = 0, binding = CACHE_BINDING, std430) buffer TexelCache { TexelEntry entries[]; };
			const uint CACHE_ENTRIES = ENTRIES_HEREu;
			const uint PROBES = 8u;
			const float FIXED_POINT = 1024.0;
			// A single sample's cap: rare very bright samples (a light picked with a small probability, seen close)
			// would otherwise flash a texel for a while. A little light near the brightest lights is lost.
			const float MAX_SAMPLE = 64.0;
			const uint EVICT = 600u;
			// An entry unseen this many frames may be taken by a new texel with nowhere else to go (the shader's
			// push constants' size.z is the frame number).
			const uint STEAL = 30u;

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

			// Bounce cells: the bounced light (B) is gathered in cells two levels coarser than the texels (a quarter
			// of a block for level 0 texels), entries of their own, which every pixel inside feeds: some 16 times a
			// texel's samples. Kept per texel, a bounce found by few rays (a dark room lit by one bright patch)
			// left each texel its own speckle for seconds; averaging neighbours when shown left a fixed pattern.
			int bounceLevel(int level) {
			    return min(level + 2, 4);
			}

			uvec2 bounceKey(vec3 p, vec3 n, bool thinSheet, ivec3 originBlock, int level) {
			    uvec2 key = texelKey(p, n, thinSheet, originBlock, bounceLevel(level));
			    uint h = texelHash(key.x ^ 0x6a09e667u);
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
			                if (previous == 0u) {
			                    entries[slot].lastFrame = size.z;
			                }
			                return int(slot);
			            }
			        }
			        // All taken (after travelling, the table fills with texels left behind, which would only be
			        // freed EVICT frames on, the new ones black till then): the one unseen longest, if unseen for
			        // STEAL frames, starts over as this texel.
			        uint oldest = 0u;
			        uint oldestAge = 0u;
			        for (uint i = 0u; i < PROBES; i++) {
			            uint slot = (key.x + i) % CACHE_ENTRIES;
			            uint age = size.z - entries[slot].lastFrame;
			            if (age > oldestAge) {
			                oldest = slot;
			                oldestAge = age;
			            }
			        }
			        if (oldestAge > STEAL) {
			            uint was = entries[oldest].key;
			            if (was != 0u && atomicCompSwap(entries[oldest].key, was, key.y) == was) {
			                entries[oldest].lastFrame = size.z;
			                entries[oldest].count = 0u;
			                entries[oldest].wiped = 0u;
			                entries[oldest].sumA = uvec4(0u);
			                entries[oldest].sumB = uvec4(0u);
			                entries[oldest].sumC = uvec4(0u);
			                entries[oldest].historyA = vec4(0.0);
			                entries[oldest].historyB = vec4(0.0);
			                entries[oldest].fastA = vec4(0.0);
			                entries[oldest].fastB = vec4(0.0);
			                entries[oldest].fastC = vec4(0.0);
			                entries[oldest].sumD = uvec4(0u);
			                entries[oldest].fastD = vec4(0.0);
			                entries[oldest].sumE = ivec4(0);
			                entries[oldest].fastE = vec4(0.0);
			                return int(oldest);
			            }
			        }
			    }
			    return -1;
			}

			// Records where a texel is (its world block and level), for blendTexel's test against the changes.
			// Written only when it differs: every pixel of a texel writing it every frame, up to a hundred at once
			// to one entry near the camera, cost more than the rays.
			void placeTexel(int slot, ivec3 block, int level) {
			    ivec4 position = ivec4(block, level);
			    if (entries[slot].position != position) {
			        entries[slot].position = position;
			    }
			}

			// A texel's level goes by its distance from the camera (texelLevel), so as the camera moves a sphere's
			// worth of surface changes level at 32, 64, 128 and 256 blocks, each texel there a new entry. They
			// started empty (no light, then a few noisy frames; their bounce cells too, which the lighting pass then
			// filled with Bedrock's sky light): a visible shell sweeping over the ground ahead. A texel just claimed
			// this frame starts instead from the history of the same spot at the next coarser level that has one
			// (or the next finer), and goes on from there.
			void copyHistory(int slot, int from) {
			    entries[slot].historyA = vec4(entries[from].historyA.rgb, min(entries[from].historyA.w, 32.0));
			    entries[slot].historyB = entries[from].historyB;
			    entries[slot].fastA = entries[from].fastA;
			    entries[slot].fastB = entries[from].fastB;
			    entries[slot].fastC = entries[from].fastC;
			    entries[slot].fastE = entries[from].fastE;
			}

			bool justClaimed(int slot) {
			    return entries[slot].lastFrame == size.z && entries[slot].historyA.w <= 0.0 && entries[slot].fastA.w <= 0.0;
			}

			void seedTexel(int slot, vec3 p, vec3 n, bool thinSheet, int level) {
			    if (!justClaimed(slot)) {
			        return;
			    }
			    int other = -1;
			    for (int up = level + 1; up <= 4 && other < 0; up++) {
			        int found = findTexel(texelKey(texelCentre(p, n, thinSheet, up), n, thinSheet, originBlock.xyz, up), false);
			        other = found >= 0 && found != slot && entries[found].historyA.w >= 2.0 ? found : -1;
			    }
			    for (int down = level - 1; down >= 0 && other < 0; down--) {
			        int found = findTexel(texelKey(texelCentre(p, n, thinSheet, down), n, thinSheet, originBlock.xyz, down),
			                false);
			        other = found >= 0 && found != slot && entries[found].historyA.w >= 2.0 ? found : -1;
			    }
			    if (other >= 0) {
			        copyHistory(slot, other);
			    }
			}

			// The same for a bounce cell (bounceKey: two levels coarser than its texels, at most 4), from the cell of
			// another texel level whose cells differ.
			void seedBounceCell(int cell, vec3 p, vec3 n, bool thinSheet, int level) {
			    if (!justClaimed(cell)) {
			        return;
			    }
			    int other = -1;
			    for (int up = level + 1; up <= 4 && other < 0; up++) {
			        if (bounceLevel(up) != bounceLevel(level)) {
			            int found = findTexel(bounceKey(p, n, thinSheet, originBlock.xyz, up), false);
			            other = found >= 0 && found != cell && entries[found].historyA.w >= 2.0 ? found : -1;
			        }
			    }
			    for (int down = level - 1; down >= 0 && other < 0; down--) {
			        if (bounceLevel(down) != bounceLevel(level)) {
			            int found = findTexel(bounceKey(p, n, thinSheet, originBlock.xyz, down), false);
			            other = found >= 0 && found != cell && entries[found].historyA.w >= 2.0 ? found : -1;
			        }
			    }
			    if (other >= 0) {
			        copyHistory(cell, other);
			    }
			}

			// Whether the world changed near a world block this frame.
			bool changedNear(ivec3 block) {
			    for (int i = 0; i < changeCount.x; i++) {
			        if (all(greaterThanEqual(block, changes[i * 2].xyz)) && all(lessThanEqual(block, changes[i * 2 + 1].xyz))) {
			            return true;
			        }
			    }
			    return false;
			}

			// A pixel's bounce samples added at once (count of them): a bounce cell takes a hundred pixels' samples
			// a frame, and adding each apart crowded its atomics.
			void addBounceSamples(int slot, vec3 sum, uint count) {
			    uvec3 fixedB = uvec3(clamp(sum, vec3(0.0), vec3(MAX_SAMPLE * float(count))) * FIXED_POINT + 0.5);
			    atomicAdd(entries[slot].sumB.x, fixedB.x);
			    atomicAdd(entries[slot].sumB.y, fixedB.y);
			    atomicAdd(entries[slot].sumB.z, fixedB.z);
			    atomicAdd(entries[slot].count, count);
			}

			// A sample's direction (where its light came from) weighed by its light, for TexelCache's E.
			void addDirection(int slot, vec3 direction, float light) {
			    int weight = int(clamp(light, 0.0, MAX_SAMPLE) * 256.0 + 0.5);
			    if (weight > 0) {
			        ivec3 weighted = ivec3(round(direction * float(weight)));
			        atomicAdd(entries[slot].sumE.x, weighted.x);
			        atomicAdd(entries[slot].sumE.y, weighted.y);
			        atomicAdd(entries[slot].sumE.z, weighted.z);
			        atomicAdd(entries[slot].sumE.w, weight);
			    }
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

			// One sample's entity terms (TexelCache's C): the emitted light the terrain let through (its luminance),
			// whether an entity stood in front of it, and, if a light entities carry was tried, whether it was seen.
			// seenColor: the colour the stained glass on the way let through (white without any).
			void addEntitySample(int slot, float emitted, bool shadowed, bool triedCarried, bool seenCarried,
			        vec3 seenColor) {
			    uint fixedLight = uint(clamp(emitted, 0.0, MAX_SAMPLE) * FIXED_POINT + 0.5);
			    if (fixedLight > 0u) {
			        atomicAdd(entries[slot].sumC.y, fixedLight);
			        if (shadowed) {
			            atomicAdd(entries[slot].sumC.x, fixedLight);
			        }
			    }
			    if (triedCarried) {
			        atomicAdd(entries[slot].sumC.w, 1u);
			        if (seenCarried) {
			            atomicAdd(entries[slot].sumC.z, 1u);
			            uvec3 fixedColor = uvec3(clamp(seenColor, 0.0, 1.0) * FIXED_POINT + 0.5);
			            atomicAdd(entries[slot].sumD.x, fixedColor.x);
			            atomicAdd(entries[slot].sumD.y, fixedColor.y);
			            atomicAdd(entries[slot].sumD.z, fixedColor.z);
			            atomicAdd(entries[slot].sumD.w, 1u);
			        }
			    }
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
			// A frame's weight in the entity terms: the lights they carry (one light at a time, little noise) follow
			// within two frames, their shadows over the emitted light (sampled among all a texel's lights) within
			// three or four.
			const float ENTITY_WEIGHT = 0.5;
			const float ENTITY_SHADOW_WEIGHT = 0.3;
			// The slow shadowed share's frames held at most, and how far the fast one may stray from it before the
			// shadow counts as moved (TUNE).
			const float ENTITY_SHADOW_FRAMES = 24.0;
			const float ENTITY_SHADOW_MOVED = 0.1;
			const uint WIPED_FRAMES = 24u;
			const float WIPED_HISTORY = 8.0;
			void blendTexel(uint slot, uint frame, float cap) {
			    if (slot >= CACHE_ENTRIES || entries[slot].key == 0u) {
			        return;
			    }
			    uint count = entries[slot].count;
			    if (changeCount.x > 0 && changedNear(entries[slot].position.xyz)) {
			        // The world changed near it: its history is forgotten, and this frame's light (or, unseen this
			        // frame, the next it gets) is taken whole.
			        entries[slot].historyA.w = 0.0;
			        entries[slot].fastA.w = 0.0;
			        entries[slot].wiped = frame;
			    }
			    // For WIPED_FRAMES after a wipe the slow average holds at most WIPED_HISTORY frames: the light bounced
			    // from surface to surface takes a few frames to build up again after it, and a plain average from the
			    // wipe on kept those first, too dark frames for a second or two.
			    if (frame - entries[slot].wiped < WIPED_FRAMES) {
			        cap = min(cap, WIPED_HISTORY);
			    }
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

			        // What entities do, over a couple of frames only, as they move: the share of the emitted light they
			        // shadow, and the share of the lights they carry seen (kept plus one: 0 is never measured).
			        uvec4 sumC = entries[slot].sumC;
			        vec4 fastC = entries[slot].fastC;
			        // The shadowed share has two averages, as A does: the fast one (x) follows a moving shadow within
			        // a few frames but is too noisy to show (blotches of texels, TV static, worst under several
			        // lights); the slow one (z, w frames held) is shown. Where the fast one strays further from it
			        // than its noise, the shadow moved: the slow one takes the fast one's value and a short history.
			        // Where an entity shadow is about, every pixel of the texel traces shadow samples of its own
			        // (RtEmissive's shadowSamples: sumB.w reached the terrain's light, sumA.w of those an entity
			        // stopped), hundreds a frame for a texel near the camera: the share from those, else from the
			        // light samples' (a few a frame, too noisy alone: it flickered like static).
			        uint shadowSeen = entries[slot].sumB.w;
			        if (shadowSeen > 0u || sumC.y > 0u) {
			            float share = shadowSeen > 0u ? float(entries[slot].sumA.w) / float(shadowSeen)
			                    : float(sumC.x) / float(sumC.y);
			            fastC.x = mix(fastC.x, share, ENTITY_SHADOW_WEIGHT);
			            float held = min(fastC.w + 1.0, ENTITY_SHADOW_FRAMES);
			            fastC.z = mix(fastC.z, share, 1.0 / held);
			            fastC.w = held;
			            if (abs(fastC.x - fastC.z) > ENTITY_SHADOW_MOVED) {
			                fastC.z = fastC.x;
			                fastC.w = 1.0 / ENTITY_SHADOW_WEIGHT;
			            }
			        }
			        if (sumC.w > 0u) {
			            float seen = float(sumC.z) / float(sumC.w);
			            fastC.y = fastC.y > 0.0 ? mix(fastC.y - 1.0, seen, ENTITY_WEIGHT) + 1.0 : seen + 1.0;
			        }
			        entries[slot].fastC = fastC;
			        entries[slot].sumC = uvec4(0u);
			        // The colour of the carried lights' rays that got through (stained glass): D, w 1 once measured.
			        uvec4 sumD = entries[slot].sumD;
			        if (sumD.w > 0u) {
			            vec3 color = vec3(sumD.xyz) / FIXED_POINT / float(sumD.w);
			            vec4 fastD = entries[slot].fastD;
			            entries[slot].fastD = vec4(fastD.w > 0.0 ? mix(fastD.rgb, color, ENTITY_WEIGHT) : color, 1.0);
			        }
			        entries[slot].sumD = uvec4(0u);
			        // Where the light comes from (E): the mean of the samples' directions weighed by their light. Its
			        // length is how much it comes from one way (1) rather than all round (0); the lighting pass gives
			        // smooth surfaces a highlight of the texel's light from there.
			        ivec4 sumE = entries[slot].sumE;
			        if (sumE.w > 0) {
			            vec3 mean = vec3(sumE.xyz) / float(sumE.w);
			            vec4 fastE = entries[slot].fastE;
			            float frames = min(fastE.w, 15.0);
			            entries[slot].fastE = vec4(mix(fastE.xyz, mean, 1.0 / (frames + 1.0)), frames + 1.0);
			        }
			        entries[slot].sumE = ivec4(0);

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
			        // Not in exports (size.w 1): there every change to the world is reported (and wipes the texels near
			        // it), the rest (the sun's path) is slow, and a reset taken on a burst of noise (a few bounce rays
			        // finding a bright patch, lava's animated glow) swapped a long, settled history for a few frames'
			        // worth: the light jumped and shimmered in hue where nothing changed. While playing, only well past
			        // the noise (5 standard deviations and a twelfth of the light).
			        bool exporting = (size.w & 1u) != 0u;
			        if (!exporting && fastA.w >= FAST
			                && abs(fastLight - slowLight) > 5.0 * fastNoise + 0.08 * slowLight + 0.001) {
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
			        entries[slot].fastC = vec4(0.0);
			        entries[slot].sumC = uvec4(0u);
			        entries[slot].sumD = uvec4(0u);
			        entries[slot].fastD = vec4(0.0);
			        entries[slot].sumE = ivec4(0);
			        entries[slot].fastE = vec4(0.0);
			        entries[slot].position = ivec4(0);
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
			// The entities' light probes (EntityProbes) after the entries.
			buffer = rt.deviceBuffer((long) ENTRIES * ENTRY_SIZE + EntityProbes.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
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
