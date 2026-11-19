package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;

/**
 * Ray-traced sun and moon shadows: for every G-buffer pixel, a ray from the point (snapped to the centre of its
 * 1/16-block texel, as the deferred lighting snaps its shadow-map lookup) towards the light through the ray-traced
 * scene (RtScene). The visibility goes to {@link #view()}, which the deferred lighting reads in place of the
 * shadow cascades' test ({@code RtShadowMap}, lighting/deferred.fsh).
 *
 * <p>Like the shadow maps: solid and cutout terrain cast (cutout alpha-tested against the block atlas with Sodium's
 * own cutoff), translucent terrain doesn't (it tints the light instead: stained glass, TRANSLUCENT_TINT), and
 * entities do. Unlike them it reaches as far as the loaded chunks,
 * and it can soften: each ray goes to a random point of the light's disc ({@link #setSunRadius}), new every frame,
 * so the offline preset's sub-frames (or TAA) average a penumbra.
 *
 * <p>The trace runs inside renderpearl's own frame (FrameCompute), after the G-buffer and before the lighting
 * pass.
 */
public final class RtShadows {
	public enum Mode {
		/** The shadow maps, as Bedrock. */
		OFF,
		/** Ray-traced shadows in place of the shadow maps' test. */
		ON,
		/** The lighting pass shows both: red where the maps light a point, green where the rays do. */
		COMPARE
	}

	/**
	 * GLSL: the colour translucent terrain lets through, for shaders declaring {@code instances}, {@code Words},
	 * {@code sodiumUv} and {@code Atlas} as RtShadows and RtEmissive do. Light through stained glass (and ice,
	 * slime, honey) takes on its colour: each face crossed lets through its texel's hue (its brightest channel at
	 * 1) by the square root of its alpha (white where clear), and under a square root again, as a block or a
	 * pane has two faces. Water, and anything else Sodium tints by biome (a vertex colour that isn't grey), lets
	 * light through untinted, as before: the lighting pass handles water's look itself.
	 */
	static final String TRANSLUCENT_TINT = """
			vec3 translucentTint(rayQueryEXT query) {
			    InstanceInfo info = instances[rayQueryGetIntersectionInstanceCustomIndexEXT(query, false)];
			    uint geometry = uint(rayQueryGetIntersectionGeometryIndexEXT(query, false));
			    if (info.kind != 0u || ((info.passes >> (2u * geometry)) & 3u) != 2u) {
			        return vec3(1.0);
			    }
			    uint primitive = uint(rayQueryGetIntersectionPrimitiveIndexEXT(query, false));
			    uvec3 corners = (primitive & 1u) == 0u ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u);
			    uint first = info.firstVertex[geometry] + (primitive >> 1u) * 4u;
			    Words words = Words(info.vertices);
			    vec3 tint = unpackUnorm4x8(words.w[first * 5u + 2u]).rgb;
			    if (max(tint.r, max(tint.g, tint.b)) - min(tint.r, min(tint.g, tint.b)) > 0.06) {
			        return vec3(1.0);
			    }
			    vec2 b = rayQueryGetIntersectionBarycentricsEXT(query, false);
			    vec2 uv = sodiumUv(words, first + corners.x) * (1.0 - b.x - b.y) + sodiumUv(words, first + corners.y) * b.x
			            + sodiumUv(words, first + corners.z) * b.y;
			    vec4 texel = textureLod(Atlas, uv, 0.0);
			    // Its hue, the brightest channel at 1 (dark glass, below a quarter, dims it too): a bright patch of
			    // sun through it keeps its colour instead of washing out to white.
			    vec3 color = pow(texel.rgb, vec3(2.2));
			    color = min(color / max(max(color.r, max(color.g, color.b)), 0.25), vec3(1.0));
			    return sqrt(mix(vec3(1.0), color, sqrt(texel.a)));
			}
			""";

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
			layout(set = 0, binding = 5, std430) writeonly buffer Visibility { uint pixels[]; };
			layout(buffer_reference, scalar, buffer_reference_align = 4) readonly buffer Words { uint w[]; };
			layout(push_constant) uniform Params {
			    mat4 inverseViewProjection; // clip space to camera-relative world space (the lighting pass's)
			    vec4 camera;                // xyz: the camera relative to the scene's origin; w: tan of the light's radius
			    vec4 light;                 // xyz: towards the sun or moon
			    uvec4 size;                 // xy: the G-buffer's size; z: the frame number (the light disc's samples)
			};

			const float ALPHA_CUTOFF[4] = float[4](0.0, 0.1, 0.5, 1.0);
			const float TEXEL_GRID = 1.0 / 16.0;

			// Sodium's texture coordinates: 15 bits of 1/32768 (the top bit only nudges them into the quad).
			vec2 sodiumUv(Words words, uint vertex) {
			    uint packed = words.w[vertex * 5u + 3u];
			    return vec2(packed & 0x7FFFu, (packed >> 16u) & 0x7FFFu) / 32768.0;
			}

			// Whether a candidate hit (non-opaque geometry) stops the ray: cutout terrain where its texel is at least
			// Sodium's alpha cutoff for the block; never translucent terrain (it casts no shadow in the maps either).
			// A plant's ray leaves from inside its own block, where its other crossed quad stands: the shadow maps (with
			    // their normal offset) don't shade a plant by itself, so a plant's ray ignores hits in its own block.
			    bool ignoreOwnBlock;
			    vec3 ownBlock;

			bool blocks(rayQueryEXT query) {
			    if (ignoreOwnBlock) {
			        vec3 hit = rayQueryGetWorldRayOriginEXT(query)
			                + rayQueryGetWorldRayDirectionEXT(query) * rayQueryGetIntersectionTEXT(query, false);
			        if (all(equal(floor(hit), ownBlock))) {
			            return false;
			        }
			    }
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

			""" + TRANSLUCENT_TINT + """
			uint hash(uint x) {
			    x ^= x >> 16u; x *= 0x7feb352du; x ^= x >> 15u; x *= 0x846ca68bu; x ^= x >> 16u;
			    return x;
			}

			void main() {
			    uvec2 pixel = gl_GlobalInvocationID.xy;
			    if (any(greaterThanEqual(pixel, size.xy))) {
			        return;
			    }
			    uint index = pixel.y * size.x + pixel.x;
			    float depth = texelFetch(Depth, ivec2(pixel), 0).r;
			    if (depth <= 0.0) {
			        pixels[index] = 0xFFFFFFFFu; // sky (reversed depth): lit
			        return;
			    }
			    vec2 texCoord = (vec2(pixel) + 0.5) / vec2(size.xy);
			    vec3 n = normalize(texelFetch(Normal, ivec2(pixel), 0).rgb * 2.0 - 1.0);
			    vec4 world = inverseViewProjection * vec4(texCoord * 2.0 - 1.0, depth, 1.0);
			    vec3 position = world.xyz / world.w;
			    vec3 towards = normalize(light.xyz);

			    // As the lighting pass: crossed plants (no axis-aligned normal) are lit from whichever side the light
			    // is on, and axis-aligned faces are shaded per 1/16-block texel, at its centre.
			    vec3 a = abs(n);
			    bool thinSheet = max(a.x, max(a.y, a.z)) < 0.9;
			    vec3 nCaster = thinSheet && dot(n, towards) < 0.0 ? -n : n;
			    if (dot(nCaster, towards) <= 0.0) {
			        pixels[index] = 0xFF000000u; // facing away: the lighting gives it no light anyway
			        return;
			    }
			    vec3 p = position + camera.xyz;
			    if (max(a.x, max(a.y, a.z)) >= 0.95) {
			        vec3 axis = round(n);
			        vec3 toCentre = TEXEL_GRID * 0.5 - (p - floor(p / TEXEL_GRID) * TEXEL_GRID);
			        p += toCentre - dot(toCentre, axis) * axis;
			    }

			    // A random point of the light's disc.
			    vec3 direction = towards;
			    if (camera.w > 0.0) {
			        uint seed = hash(index * 9781u + size.z * 6271u);
			        float r = sqrt(float(seed & 0xFFFFu) / 65536.0) * camera.w;
			        float phi = float(seed >> 16u) / 65536.0 * 6.2831853;
			        vec3 side = normalize(cross(abs(towards.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), towards));
			        vec3 up = cross(towards, side);
			        direction = normalize(towards + (side * cos(phi) + up * sin(phi)) * r);
			    }

			    ignoreOwnBlock = thinSheet;
			    ownBlock = floor(p);
			    rayQueryEXT query;
			    rayQueryInitializeEXT(query, scene, gl_RayFlagsTerminateOnFirstHitEXT, 0xFF, p + nCaster * 0.01, 0.001,
			            direction, 2048.0);
			    // Translucent terrain on the way tints the light (TRANSLUCENT_TINT); rgb: the light let through.
			    vec3 through = vec3(1.0);
			    while (rayQueryProceedEXT(query)) {
			        if (rayQueryGetIntersectionTypeEXT(query, false) == gl_RayQueryCandidateIntersectionTriangleEXT) {
			            if (blocks(query)) {
			                rayQueryConfirmIntersectionEXT(query);
			            } else {
			                through *= translucentTint(query);
			            }
			        }
			    }
			    bool shadowed = rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
			    pixels[index] = shadowed ? 0xFF000000u : packUnorm4x8(vec4(through, 1.0));
			}
			""";

	private static final FrameCompute PASS = new FrameCompute("ray-traced shadows", SHADER, new int[] {
			VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
			VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
			VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER}, 112, 4, GpuFormat.RGBA8_UNORM);

	private static Mode mode = Mode.OFF;
	/** The light's angular radius (degrees): the real sun's by default; 0 for hard shadows. */
	private static float sunRadius = 0.27f;
	private static boolean failed;
	private static String failure = "";
	private static boolean tracedThisFrame;
	private static long frameNumber;
	private static long traces;

	private RtShadows() {
	}

	public static Mode mode() {
		return mode;
	}

	/** Turns the ray-traced shadows on (with the scene they trace through), off, or to the comparison view. */
	public static void setMode(Mode newMode) {
		mode = newMode;
		failed = false;
		if (newMode != Mode.OFF) {
			RtScene.setEnabled(true);
		}
	}

	public static float sunRadius() {
		return sunRadius;
	}

	/** The light's angular radius in degrees (0: hard shadows). */
	public static void setSunRadius(float degrees) {
		sunRadius = Math.max(degrees, 0);
	}

	/** Whether the lighting pass reads {@link #view()} this frame. */
	public static boolean active() {
		return tracedThisFrame;
	}

	public static boolean comparing() {
		return tracedThisFrame && mode == Mode.COMPARE;
	}

	/** The visibility (red: 1 lit, 0 shadowed), when {@link #active()}. */
	public static GpuTextureView view() {
		return PASS.view();
	}

	/**
	 * Traces this frame's shadows, after the G-buffer is drawn and before the lighting pass reads it.
	 *
	 * @param projection, modelView the matrices the G-buffer was drawn with
	 * @param towardsLight towards the light that casts this frame's shadows (the sun, or the moon at night)
	 */
	public static void trace(GpuTextureView depth, GpuTextureView normal, GpuTextureView atlas, Matrix4fc projection,
			Matrix4fc modelView, Vector3fc towardsLight) {
		tracedThisFrame = false;
		if (mode == Mode.OFF || failed) {
			return;
		}
		RtScene.Frame frame = RtScene.ensureBuilt();
		if (frame == null) {
			return;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int w = depth.getWidth(0);
			int h = depth.getHeight(0);
			Matrix4f inverse = new Matrix4f(projection).mul(modelView).invert();
			ByteBuffer constants = stack.malloc(112);
			inverse.get(0, constants);
			constants.putFloat(64, (float) (frame.cameraX() - frame.originX()));
			constants.putFloat(68, (float) (frame.cameraY() - frame.originY()));
			constants.putFloat(72, (float) (frame.cameraZ() - frame.originZ()));
			constants.putFloat(76, (float) Math.tan(Math.toRadians(sunRadius)));
			constants.putFloat(80, towardsLight.x()).putFloat(84, towardsLight.y()).putFloat(88, towardsLight.z())
					.putFloat(92, 0);
			constants.putInt(96, w).putInt(100, h).putInt(104, (int) frameNumber++).putInt(108, 0);
			PASS.dispatch(RtDevice.get(), w, h, List.of(new FrameCompute.Structure(frame.tlas()),
					new FrameCompute.Buffer(frame.instances().buffer()), new FrameCompute.Image(depth),
					new FrameCompute.Image(normal), new FrameCompute.Image(atlas)), constants);
			tracedThisFrame = true;
			traces++;
		} catch (RuntimeException e) {
			failed = true;
			failure = e.getMessage();
			BedrockVisualsClient.LOGGER.error("[raytrace] ray-traced shadows failed; back to the shadow maps", e);
		}
	}

	/** A line for the status report. */
	public static String status() {
		if (failed) {
			return "FAILED (" + failure + ")";
		}
		if (mode == Mode.OFF) {
			return "off (the shadow maps)";
		}
		return String.format(Locale.ROOT, "%s, light radius %.2f degrees; %s this frame, %d traces", mode
				.name().toLowerCase(Locale.ROOT), sunRadius, tracedThisFrame ? "traced" : "not traced", traces);
	}
}
