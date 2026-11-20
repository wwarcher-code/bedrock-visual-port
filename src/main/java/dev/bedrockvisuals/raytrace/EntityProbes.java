package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The ray-traced light for entities (lit forward by entity/entity.fsh, off the G-buffer and the texel cache): a
 * grid of light probes around the camera, one a block, {@link #X} x {@link #Y} x {@link #Z} blocks. Each frame
 * every probe takes {@code SAMPLES} samples of the light from emissive texels, as the texel cache's (RtEmissive's
 * light grid, shadow rays through the scene), from every direction, kept as an ambient cube: the light arriving
 * on a surface facing each of the six axes. They are averaged over up to {@code HISTORY} frames, and start over
 * where the world changes, as the texel cache does. Probes inside solid blocks (the light volume's flag) are
 * marked empty, so an entity standing on a floor isn't darkened by the probe in it.
 *
 * <p>The probes are kept after the texel cache's entries in its buffer (TexelCache.PROBE_BYTES), {@code Probe}
 * each: its world block (w: 1 once used), then six faces of rgb and the frames held (w). Their store is wrapped
 * by world position, so the grid follows the camera without copying.
 *
 * <p>With the bounce light on, each probe also keeps the light bounced to it (as the texels get it: the sun, moon
 * and cached light off the surfaces its rays meet), {@code BOUNCE_SAMPLES} rays a frame over the whole sphere, as a
 * second ambient cube: entities in a sunlit room get the light its floor and walls send back, as the terrain does.
 *
 * <p>The output is an atlas of {@link #ATLAS_WIDTH} x {@link #ATLAS_HEIGHT} texels (RGBA16F): the probe at grid
 * cell (x, y, z) at texel (x * 13 + face, z + y * Z), faces +x, -x, +y, -y, +z, -z of the emitted light, then the
 * same six of the bounced light; alpha 1 for a probe in the open, 0 inside a solid block. Entities sample it by their position relative to {@link #cornerX} and so on;
 * the lights entities carry they add themselves (unshadowed), from EntityLights' list.
 */
public final class EntityProbes {
	public static final int X = 48;
	public static final int Y = 24;
	public static final int Z = 48;
	static final int COUNT = X * Y * Z;
	static final int PROBE_SIZE = 16 + 12 * 16;
	static final long BYTES = (long) COUNT * PROBE_SIZE;
	/** Texels a probe: its twelve faces, then the light from every direction (for the air and water's haze). */
	static final int ATLAS_WIDTH = X * 13;
	static final int ATLAS_HEIGHT = Y * Z;

	private static final String SOURCE = """
			#version 460
			#extension GL_EXT_ray_query : require
			#extension GL_EXT_buffer_reference : require
			#extension GL_EXT_buffer_reference_uvec2 : require
			#extension GL_EXT_scalar_block_layout : require
			layout(local_size_x = 64) in;
			#define OUTPUT_BINDING 11
			""" + RtEmissive.COMMON + """
			layout(set = 0, binding = 10) uniform sampler2D LightVolumeMap;
			struct Probe { ivec4 cell; vec4 face[12]; };
			layout(set = 0, binding = CACHE_BINDING, std430) buffer ProbeStore { TexelEntry cacheEntries[CACHE_ENTRIES]; Probe probes[]; };
			const ivec3 GRID = ivec3(PROBES_X, PROBES_Y, PROBES_Z);
			const int SAMPLES = 8;
			// Bounce rays a probe a frame, in directions over the whole sphere (RtEmissive's bounceRadiance).
			const int BOUNCE_SAMPLES = 4;
			const float HISTORY = 24.0;
			// Carried lights each probe measures the share of it sees (entity.fsh's RT_CARRIED_SEEN).
			const int CARRIED_SEEN = 8;
			int seenFace(int i) {
			    return i < 4 ? 2 + i : 4 + i;
			}

			// Whether the block at a world position is solid and opaque (the light volume's blue, LightVolume).
			bool solidAt(ivec3 world) {
			    ivec3 size = ivec3(128, 48, 128);
			    ivec3 c = world - size * ivec3(floor(vec3(world) / vec3(size)));
			    return texelFetch(LightVolumeMap, ivec2((c.y % 8) * 128 + c.x, (c.y / 8) * 128 + c.z), 0).b > 0.5;
			}

			// One sample of the light from emissive texels arriving at point p (g in the light grid) from any
			// direction: the light (emissiveSample's, without a receiving surface's cosine) and its direction.
			vec3 probeSample(vec3 p, vec3 g, uint count, uint indexBase, float cumulative[PER_CELL], float total, vec3 u,
			        out vec3 direction) {
			    direction = vec3(0.0, 1.0, 0.0);
			    if (total <= 0.0) {
			        return vec3(0.0);
			    }
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
			    float probability = (cumulative[low] - (low > 0u ? cumulative[low - 1u] : 0.0)) / total;
			    if (probability <= 0.0) {
			        return vec3(0.0);
			    }
			    Light light = lights[cellWords[indexBase + low] & 0x7FFFFFFFu];
			    vec3 x = light.p0.xyz + light.e1.xyz * u.y + light.e2.xyz * u.z;
			    vec2 uv = light.uv.xy + light.uv.zw * u.y + light.uv2.xy * u.z;
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
			    direction = toPoint / d;
			    vec3 lightNormal = cross(light.e1.xyz, light.e2.xyz);
			    float area = length(lightNormal);
			    float cosLight = abs(dot(lightNormal / area, direction));
			    vec3 through = cosLight > 0.0 ? transmittance(p, direction, max(d - 0.03, 0.002), 0xFFu) : vec3(0.0);
			    if (dot(through, vec3(1.0)) <= 0.0) {
			        return vec3(0.0);
			    }
			    vec3 glow = pow(texel.rgb, vec3(2.2)) * emissive * 2.0 * through * window;
			    return glow * cosLight * area / max(d * d, 1.0 / 64.0) / probability * switches.w / PI;
			}

			void main() {
			    int index = int(gl_GlobalInvocationID.x);
			    if (index >= GRID.x * GRID.y * GRID.z) {
			        return;
			    }
			    ivec3 local = ivec3(index % GRID.x, (index / GRID.x) % GRID.y, index / (GRID.x * GRID.y));
			    ivec3 world = originBlock.xyz - GRID / 2 + local;
			    ivec3 wrapped = world - GRID * ivec3(floor(vec3(world) / vec3(GRID)));
			    uint slot = uint((wrapped.z * GRID.y + wrapped.y) * GRID.x + wrapped.x);
			    uint out0 = uint((local.z + local.y * GRID.z) * GRID.x * 13 + local.x * 13);

			    Probe probe = probes[slot];
			    if (probe.cell.xyz != world || probe.cell.w == 0 || (changeCount.x > 0 && changedNear(world))) {
			        for (int i = 0; i < 12; i++) {
			            probe.face[i] = vec4(0.0);
			        }
			        probe.cell = ivec4(world, 1);
			    }
			    bool solid = entityCount.z != 0 && solidAt(world);
			    if (!solid && (switches.x > 0.5 || switches.y > 0.5)) {
			        vec3 p = vec3(world - originBlock.xyz) + 0.5;
			        vec3 g = p + grid.xyz;
			        // The light grid's cell, looked up up to half a cell away at random each frame (as for the texels).
			        uint h = texelHash(uint(index) ^ texelHash(size.z * 0x9e3779b9u));
			        vec3 jitter = (vec3(h & 0x3FFu, (h >> 10u) & 0x3FFu, (h >> 20u) & 0x3FFu) / 1024.0 - 0.5) * CELL;
			        uint base = 0u;
			        uint indexBase = 0u;
			        uint count = switches.x > 0.5 && gridCell(g + jitter, base, indexBase) ? floatBitsToUint(cells[base].x)
			                : 0u;
			        float cumulative[PER_CELL];
			        float total = 0.0;
			        for (uint i = 0u; i < count; i++) {
			            vec4 entry = cells[base + 1u + i];
			            vec3 toLight = entry.xyz - g;
			            float d2 = max(dot(toLight, toLight), 0.25);
			            total += entry.w / d2 * reachWindow(sqrt(d2), reachOf(entry.w));
			            cumulative[i] = total;
			        }
			        sequencePixel = uint(index) + 0x51ed27u;
			        state = uint(index) * 9781u + size.z * 6271u + 1u;
			        vec3 sum[6] = vec3[6](vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0));
			        for (int s = 0; s < SAMPLES; s++) {
			            vec3 direction;
			            vec3 light = probeSample(p, g, count, indexBase, cumulative, total, sequence3(uint(s)), direction);
			            sum[0] += light * max(direction.x, 0.0);
			            sum[1] += light * max(-direction.x, 0.0);
			            sum[2] += light * max(direction.y, 0.0);
			            sum[3] += light * max(-direction.y, 0.0);
			            sum[4] += light * max(direction.z, 0.0);
			            sum[5] += light * max(-direction.z, 0.0);
			        }
			        // The bounced light, as the texels get it (RtEmissive's bounceRadiance), from every direction: with
			        // directions uniform over the sphere (pdf 1 / 4 pi), each face's light over pi is 4 x the radiance x
			        // the cosine.
			        vec3 bounced[6] = vec3[6](vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0), vec3(0.0));
			        if (switches.y > 0.5) {
			            for (int s = 0; s < BOUNCE_SAMPLES; s++) {
			                vec3 u = sequence3(uint(s) + 64u);
			                float z = u.x * 2.0 - 1.0;
			                float phi = u.y * 2.0 * PI;
			                vec3 direction = vec3(sqrt(max(1.0 - z * z, 0.0)) * vec2(cos(phi), sin(phi)), z);
			                vec3 light = min(bounceRadiance(p, direction), vec3(MAX_SAMPLE)) * 4.0;
			                bounced[0] += light * max(direction.x, 0.0);
			                bounced[1] += light * max(-direction.x, 0.0);
			                bounced[2] += light * max(direction.y, 0.0);
			                bounced[3] += light * max(-direction.y, 0.0);
			                bounced[4] += light * max(direction.z, 0.0);
			                bounced[5] += light * max(-direction.z, 0.0);
			            }
			        }
			        // How much of each of the first CARRIED_SEEN lights entities carry the probe sees, through the
			        // terrain (2 rays to points of its sphere, averaged with the last frames at half weight): entity.fsh
			        // shades entities by it, so a torch carried behind a wall no longer lights a mob through it. Kept in
			        // the alphas the entities don't read (faces 2 to 5 and 8 to 11).
			        float seenNow[CARRIED_SEEN];
			        uint carriedHere = min(uint(entityCount.x), uint(CARRIED_SEEN));
			        for (int i = 0; i < CARRIED_SEEN; i++) {
			            seenNow[i] = 1.0;
			            if (uint(i) < carriedHere) {
			                vec4 at = entityLights[i * 2];
			                vec4 carriedLight = entityLights[i * 2 + 1];
			                float d = length(at.xyz - p);
			                if (d < carriedLight.w + 1.0 && d > 0.05) {
			                    float hits = 0.0;
			                    for (int s = 0; s < 2; s++) {
			                        float z = random() * 2.0 - 1.0;
			                        float phi = random() * 2.0 * PI;
			                        vec3 x = at.xyz + vec3(sqrt(max(1.0 - z * z, 0.0)) * vec2(cos(phi), sin(phi)), z) * at.w;
			                        vec3 toPoint = x - p;
			                        float dist = length(toPoint);
			                        if (dist < 0.05 || !occludedBy(p, toPoint / dist, max(dist - 0.05, 0.002), 0x01u)) {
			                            hits += 1.0;
			                        }
			                    }
			                    seenNow[i] = hits * 0.5;
			                }
			            }
			        }
			        float held = probe.face[0].w;
			        float weight = 1.0 / (held + 1.0);
			        // The shares kept last frame, read before the faces below overwrite their alphas.
			        float seenBefore[CARRIED_SEEN];
			        for (int i = 0; i < CARRIED_SEEN; i++) {
			            seenBefore[i] = probe.face[seenFace(i)].w;
			        }
			        for (int i = 0; i < 6; i++) {
			            probe.face[i] = vec4(mix(probe.face[i].rgb, min(sum[i] / float(SAMPLES), vec3(MAX_SAMPLE)), weight),
			                    min(held + 1.0, HISTORY));
			            probe.face[6 + i] = vec4(mix(probe.face[6 + i].rgb, bounced[i] / float(BOUNCE_SAMPLES), weight), 0.0);
			        }
			        for (int i = 0; i < CARRIED_SEEN; i++) {
			            int f = seenFace(i);
			            probe.face[f].w = held < 0.5 ? seenNow[i] : mix(clamp(seenBefore[i], 0.0, 1.0), seenNow[i], 0.5);
			        }
			    }
			    probes[slot] = probe;
			    for (int i = 0; i < 12; i++) {
			        vec3 light = solid ? vec3(0.0) : probe.face[i].rgb;
			        // Alpha: faces 0, 1, 6 and 7 whether the probe is in the open (what entity.fsh weighs by); the
			        // others the share of a carried light it sees.
			        bool flag = i == 0 || i == 1 || i == 6 || i == 7;
			        float alpha = flag ? (solid ? 0.0 : 1.0) : (probe.cell.w == 0 ? 1.0 : probe.face[i].w);
			        pixels[out0 + uint(i)] = uvec2(packHalf2x16(light.rg), packHalf2x16(vec2(light.b, alpha)));
			    }
			    // The thirteenth: the mean of the six faces, emitted and (when on) bounced, for the light the air and
			    // water scatter (rt_probes.glsl's rtProbesAverage): read there by every step of the fog, the 96 reads
			    // it took from the faces cost 10 ms a frame. Alpha: in the open.
			    vec3 around = vec3(0.0);
			    for (int i = 0; i < 6; i++) {
			        around += probe.face[i].rgb + (switches.y > 0.5 ? probe.face[6 + i].rgb : vec3(0.0));
			    }
			    around = solid ? vec3(0.0) : around / 6.0;
			    pixels[out0 + 12u] = uvec2(packHalf2x16(around.rg), packHalf2x16(vec2(around.b, solid ? 0.0 : 1.0)));
			}
			""".replace("PROBES_X", Integer.toString(X)).replace("PROBES_Y", Integer.toString(Y))
			.replace("PROBES_Z", Integer.toString(Z));

	private static final FrameCompute PASS = new FrameCompute("ray-traced light for entities", new String[] {SOURCE},
			new int[] {VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
					VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
					VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
					VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
					VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
					VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER}, 128, 8, GpuFormat.RGBA16_FLOAT);

	private static boolean active;
	private static int cornerX;
	private static int cornerY;
	private static int cornerZ;
	private static double cameraX;
	private static double cameraY;
	private static double cameraZ;

	private EntityProbes() {
	}

	/** A frame without the probes (the traced light off, or failed). */
	static void skip() {
		active = false;
	}

	/**
	 * Traces this frame's probes, with the emitted light's bindings (0 to 9) and push constants, and the light
	 * volume (for solid blocks; null if there is none).
	 */
	static void dispatch(RtDevice rt, List<FrameCompute.Binding> emissiveBindings, ByteBuffer constants,
			GpuTextureView lightVolume, GpuTextureView fallback, RtScene.Frame frame) {
		List<FrameCompute.Binding> bindings = new ArrayList<>(emissiveBindings);
		bindings.add(new FrameCompute.Image(lightVolume != null ? lightVolume : fallback));
		PASS.dispatch(rt, ATLAS_WIDTH, ATLAS_HEIGHT, bindings, constants, new int[][] {{(COUNT + 63) / 64, 1, 1}});
		cornerX = frame.originX() - X / 2;
		cornerY = frame.originY() - Y / 2;
		cornerZ = frame.originZ() - Z / 2;
		cameraX = frame.cameraX();
		cameraY = frame.cameraY();
		cameraZ = frame.cameraZ();
		active = true;
	}

	/** Whether entities read the probes this frame. */
	public static boolean active() {
		return active;
	}

	public static GpuTextureView view() {
		return PASS.view();
	}

	/** The grid's lowest corner relative to the camera the probes were traced for (blocks). */
	public static float cornerRelativeX() {
		return (float) (cornerX - cameraX);
	}

	public static float cornerRelativeY() {
		return (float) (cornerY - cameraY);
	}

	public static float cornerRelativeZ() {
		return (float) (cornerZ - cameraZ);
	}

	/** The camera the probes were traced for, in world blocks. */
	public static double cameraX() {
		return cameraX;
	}

	public static double cameraY() {
		return cameraY;
	}

	public static double cameraZ() {
		return cameraZ;
	}
}
