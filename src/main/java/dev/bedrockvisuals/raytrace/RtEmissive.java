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
import org.lwjgl.system.MemoryStack;

/**
 * Ray-traced light from emissive texels (EmissiveLights): for every G-buffer pixel, at the centre of its 1/16
 * texel, one light picked from its grid cell's list in proportion to how much it would give the point unshadowed,
 * a random point of that light's quad, its texel's glow there (the block atlas colour times Bedrock's emissive,
 * times 2, as the lighting pass draws emissive surfaces), and a shadow ray to it. New picks every frame, so the
 * offline preset's sub-frames (or TAA) average them into soft, coloured light whose shadows widen with distance
 * from the light.
 *
 * <p>The result ({@link #view()}, RGBA16F) is the light that reaches the point divided by pi, in the units of
 * Bedrock's block light term (alpha 1 on a stand-in light's own surface, which keeps Bedrock's block light): the lighting pass multiplies it by the diffuse colour, in place of Bedrock's block
 * light ({@link Mode#REPLACE}) or over it as a floor ({@link Mode#FLOOR}).
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

	private static final String SHADER = """
			#version 460
			#extension GL_EXT_ray_query : require
			#extension GL_EXT_buffer_reference : require
			#extension GL_EXT_buffer_reference_uvec2 : require
			#extension GL_EXT_scalar_block_layout : require
			layout(local_size_x = 8, local_size_y = 8) in;
			layout(set = 0, binding = 0) uniform accelerationStructureEXT scene;
			struct InstanceInfo { uvec2 vertices; uint kind; uint stride; uint firstVertex[3]; uint passes; };
			layout(set = 0, binding = 1, std430) readonly buffer Instances { InstanceInfo instances[]; };
			layout(set = 0, binding = 2) uniform sampler2D Depth;
			layout(set = 0, binding = 3) uniform sampler2D Normal;
			layout(set = 0, binding = 4) uniform sampler2D Atlas;
			layout(set = 0, binding = 5) uniform sampler2D Materials;
			struct Light { vec4 p0; vec4 e1; vec4 e2; vec4 uv; vec4 uv2; };
			layout(set = 0, binding = 6, std430) readonly buffer Lights { Light lights[]; };
			// Per cell, PER_CELL + 1 vec4s (its count; then each light's centre and power); then each entry's light
			// index, the top bit set for a light that stands in for block light (EmissiveLights).
			layout(set = 0, binding = 7, std430) readonly buffer Cells { vec4 cells[]; };
			layout(set = 0, binding = 7, std430) readonly buffer CellIndices { uint cellWords[]; };
			layout(set = 0, binding = 8, std430) writeonly buffer Irradiance { uvec2 pixels[]; };
			layout(buffer_reference, scalar, buffer_reference_align = 4) readonly buffer Words { uint w[]; };
			layout(push_constant) uniform Params {
			    mat4 inverseViewProjection; // clip space to camera-relative world space (the lighting pass's)
			    vec4 camera;                // xyz: the camera relative to the scene's origin; w: the light's gain
			    vec4 grid;                  // xyz: the scene's origin relative to the light grid's corner; w: samples
			    uvec4 size;                 // xy: the G-buffer's size; z: the frame number
			};

			const int CELLS_X = 24;
			const int CELLS_Y = 12;
			const int CELLS_Z = 24;
			const int PER_CELL = 48;
			const float CELL = 4.0;
			const float ALPHA_CUTOFF[4] = float[4](0.0, 0.1, 0.5, 1.0);
			const float TEXEL_GRID = 1.0 / 16.0;

			vec2 sodiumUv(Words words, uint vertex) {
			    uint packed = words.w[vertex * 5u + 3u];
			    return vec2(packed & 0x7FFFu, (packed >> 16u) & 0x7FFFu) / 32768.0;
			}

			// As the shadows: cutout terrain alpha-tested, translucent terrain passing light, entities blocking.
			bool blocks(rayQueryEXT query) {
			    InstanceInfo info = instances[rayQueryGetIntersectionInstanceCustomIndexEXT(query, false)];
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

			uint state;
			float random() {
			    state = state * 747796405u + 2891336453u;
			    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
			    return float((word >> 22u) ^ word) / 4294967296.0;
			}

			void main() {
			    uvec2 pixel = gl_GlobalInvocationID.xy;
			    if (any(greaterThanEqual(pixel, size.xy))) {
			        return;
			    }
			    uint index = pixel.y * size.x + pixel.x;
			    float depth = texelFetch(Depth, ivec2(pixel), 0).r;
			    if (depth <= 0.0) {
			        pixels[index] = uvec2(0u);
			        return;
			    }
			    state = index * 9781u + size.z * 6271u + 1u;
			    vec2 texCoord = (vec2(pixel) + 0.5) / vec2(size.xy);
			    vec3 n = normalize(texelFetch(Normal, ivec2(pixel), 0).rgb * 2.0 - 1.0);
			    vec4 world = inverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
			    vec3 p = world.xyz / world.w + camera.xyz;
			    vec3 a = abs(n);
			    bool thinSheet = max(a.x, max(a.y, a.z)) < 0.9;
			    if (!thinSheet && max(a.x, max(a.y, a.z)) >= 0.95) {
			        // Shaded per 1/16-block texel, at its centre.
			        vec3 axis = round(n);
			        vec3 toCentre = TEXEL_GRID * 0.5 - (p - floor(p / TEXEL_GRID) * TEXEL_GRID);
			        p += toCentre - dot(toCentre, axis) * axis;
			    }
			    vec3 g = p + grid.xyz;
			    ivec3 cell = ivec3(floor(g / CELL));
			    if (any(lessThan(cell, ivec3(0))) || any(greaterThanEqual(cell, ivec3(CELLS_X, CELLS_Y, CELLS_Z)))) {
			        pixels[index] = uvec2(0u);
			        return;
			    }
			    uint cellIndex = uint((cell.z * CELLS_Y + cell.y) * CELLS_X + cell.x);
			    uint base = cellIndex * uint(PER_CELL + 1);
			    uint count = floatBitsToUint(cells[base].x);
			    uint indexBase = uint(CELLS_X * CELLS_Y * CELLS_Z * (PER_CELL + 1)) * 4u + cellIndex * uint(PER_CELL);

			    // How much each of the cell's lights would give the point, unshadowed, from its centre.
			    float weights[PER_CELL];
			    float total = 0.0;
			    for (uint i = 0u; i < count; i++) {
			        vec4 entry = cells[base + 1u + i];
			        vec3 toLight = entry.xyz - g;
			        float d2 = max(dot(toLight, toLight), 0.25);
			        float facing = dot(n, toLight) * inversesqrt(d2);
			        facing = thinSheet ? abs(facing) : facing;
			        float w = entry.w * clamp(facing * 0.8 + 0.2, 0.0, 1.0) / d2;
			        weights[i] = w;
			        total += w;
			    }
			    // A point on a light that only stands in for Bedrock's block light (lava: its own glow comes from block
			    // light there, not from its texels): marked, so the lighting keeps Bedrock's block light on it.
			    float standIn = 0.0;
			    for (uint i = 0u; i < count; i++) {
			        uint word = cellWords[indexBase + i];
			        if ((word & 0x80000000u) == 0u) {
			            continue;
			        }
			        Light light = lights[word & 0x7FFFFFFFu];
			        vec3 d = g - light.p0.xyz;
			        vec3 lightNormal = normalize(cross(light.e1.xyz, light.e2.xyz));
			        float s = dot(d, light.e1.xyz) / dot(light.e1.xyz, light.e1.xyz);
			        float t = dot(d, light.e2.xyz) / dot(light.e2.xyz, light.e2.xyz);
			        if (abs(dot(d, lightNormal)) < 0.02 && s >= -0.01 && s <= 1.01 && t >= -0.01 && t <= 1.01) {
			            standIn = 1.0;
			            break;
			        }
			    }
			    vec3 sum = vec3(0.0);
			    int samples = max(int(grid.w), 1);
			    if (total > 0.0) {
			        for (int sampleIndex = 0; sampleIndex < samples; sampleIndex++) {
			            // A light, in proportion to its weight.
			            float pick = random() * total;
			            uint chosen = 0u;
			            for (uint i = 0u; i < count; i++) {
			                chosen = i;
			                pick -= weights[i];
			                if (pick <= 0.0) {
			                    break;
			                }
			            }
			            float probability = weights[chosen] / total;
			            Light light = lights[cellWords[indexBase + chosen] & 0x7FFFFFFFu];
			            // A random point of it, and its texel's glow.
			            float s = random();
			            float t = random();
			            vec3 x = light.p0.xyz + light.e1.xyz * s + light.e2.xyz * t;
			            vec2 uv = light.uv.xy + light.uv.zw * s + light.uv2.xy * t;
			            vec4 texel = textureLod(Atlas, uv, 0.0);
			            float emissive = max(textureLod(Materials, uv, 0.0).g, light.uv2.z);
			            if (emissive <= 0.0 || (light.e2.w > 0.0 && texel.a < light.e2.w)) {
			                continue;
			            }
			            vec3 glow = pow(texel.rgb, vec3(2.2)) * emissive * 2.0;
			            vec3 toPoint = x - g;
			            float d = length(toPoint);
			            if (d < 0.05) {
			                continue;
			            }
			            vec3 direction = toPoint / d;
			            float cosReceiver = dot(n, direction);
			            vec3 nRay = n;
			            if (thinSheet && cosReceiver < 0.0) {
			                cosReceiver = -cosReceiver;
			                nRay = -n;
			            }
			            vec3 lightNormal = cross(light.e1.xyz, light.e2.xyz);
			            float area = length(lightNormal);
			            float cosLight = abs(dot(lightNormal / area, direction));
			            if (cosReceiver <= 0.0 || cosLight <= 0.0) {
			                continue;
			            }
			            rayQueryEXT query;
			            rayQueryInitializeEXT(query, scene, gl_RayFlagsTerminateOnFirstHitEXT, 0xFF, p + nRay * 0.01, 0.001,
			                    direction, max(d - 0.03, 0.002));
			            while (rayQueryProceedEXT(query)) {
			                if (rayQueryGetIntersectionTypeEXT(query, false) == gl_RayQueryCandidateIntersectionTriangleEXT
			                        && blocks(query)) {
			                    rayQueryConfirmIntersectionEXT(query);
			                }
			            }
			            if (rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT) {
			                continue;
			            }
			            sum += glow * cosReceiver * cosLight * area / max(d * d, 1.0 / 64.0) / probability;
			        }
			    }
			    vec3 irradiance = sum / float(samples) * camera.w / 3.14159265;
			    pixels[index] = uvec2(packHalf2x16(irradiance.rg), packHalf2x16(vec2(irradiance.b, standIn)));
			}
			""";

	private static final FrameCompute PASS = new FrameCompute("ray-traced emissive light", SHADER, new int[] {
			VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
			VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
			VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
			VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER}, 112, 8, GpuFormat.RGBA16_FLOAT);

	private static Mode mode = Mode.OFF;
	/**
	 * Scales the light (1: the emissive texels' glow as the lighting pass draws them). 10 matches Bedrock's block
	 * light a block or two from glowstone (test scene, same exposure); larger glowing faces (sea lanterns,
	 * shroomlights) come out brighter than Bedrock's and small ones (torches) dimmer.
	 */
	private static float gain = 10.0f;
	/** In {@link Mode#FLOOR}: the share of Bedrock's block light kept as the least light anywhere. */
	private static float floor = 0.35f;
	private static int samples = 1;
	/** Samples a pixel in each of the offline preset's sub-frames (its 8 sub-frames then average 32). */
	private static int offlineSamples = 4;
	private static boolean failed;
	private static String failure = "";
	private static boolean tracedThisFrame;
	private static long frameNumber;

	private RtEmissive() {
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

	public static void setGain(float value) {
		gain = Math.max(value, 0);
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

	/** Whether the lighting pass reads {@link #view()} this frame. */
	public static boolean active() {
		return tracedThisFrame;
	}

	/** The share of Bedrock's block light the lighting pass keeps under the traced light (1 in place of it: 0). */
	public static float floorShare() {
		return mode == Mode.FLOOR ? floor : 0;
	}

	public static GpuTextureView view() {
		return PASS.view();
	}

	/** Traces this frame's emissive light, after the G-buffer is drawn and before the lighting pass reads it. */
	public static void trace(GpuTextureView depth, GpuTextureView normal, GpuTextureView atlas, Matrix4fc projection,
			Matrix4fc modelView) {
		tracedThisFrame = false;
		if (mode == Mode.OFF || failed) {
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
			Matrix4f inverse = new Matrix4f(projection).mul(modelView).invert();
			ByteBuffer constants = stack.malloc(112);
			inverse.get(0, constants);
			constants.putFloat(64, (float) (frame.cameraX() - frame.originX()));
			constants.putFloat(68, (float) (frame.cameraY() - frame.originY()));
			constants.putFloat(72, (float) (frame.cameraZ() - frame.originZ()));
			constants.putFloat(76, gain);
			constants.putFloat(80, frame.originX() - grid.minX()).putFloat(84, frame.originY() - grid.minY())
					.putFloat(88, frame.originZ() - grid.minZ())
					.putFloat(92, dev.bedrockvisuals.render.OfflineRender.active() ? offlineSamples : samples);
			constants.putInt(96, w).putInt(100, h).putInt(104, (int) frameNumber++).putInt(108, 0);
			PASS.dispatch(rt, w, h, List.of(new FrameCompute.Structure(frame.tlas()),
					new FrameCompute.Buffer(frame.instances().buffer()), new FrameCompute.Image(depth),
					new FrameCompute.Image(normal), new FrameCompute.Image(atlas), new FrameCompute.Image(materials),
					new FrameCompute.Buffer(grid.lights().buffer()), new FrameCompute.Buffer(grid.cells().buffer())),
					constants);
			tracedThisFrame = true;
		} catch (RuntimeException e) {
			failed = true;
			failure = e.getMessage();
			BedrockVisualsClient.LOGGER.error("[raytrace] ray-traced emissive light failed; back to block light", e);
		}
	}

	/** A line for the status report. */
	public static String status() {
		if (failed) {
			return "FAILED (" + failure + ")";
		}
		if (mode == Mode.OFF) {
			return "off (Bedrock's block light)";
		}
		return String.format(Locale.ROOT, "%s, gain %.2f, floor %.2f, %d samples (%d a sub-frame in exports); %s; %s this frame",
				mode.name().toLowerCase(Locale.ROOT), gain, floor, samples, offlineSamples, EmissiveLights.status(),
				tracedThisFrame ? "traced" : "not traced");
	}
}
