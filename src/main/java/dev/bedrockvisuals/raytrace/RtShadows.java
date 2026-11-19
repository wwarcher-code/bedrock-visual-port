package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * Ray-traced sun and moon shadows: for every G-buffer pixel, a ray from the point (snapped to the centre of its
 * 1/16-block texel, as the deferred lighting snaps its shadow-map lookup) towards the light through the ray-traced
 * scene (RtScene). The visibility goes to {@link #view()}, which the deferred lighting reads in place of the
 * shadow cascades' test ({@code RtShadowMap}, lighting/deferred.fsh).
 *
 * <p>Like the shadow maps: solid and cutout terrain cast (cutout alpha-tested against the block atlas with Sodium's
 * own cutoff), translucent terrain doesn't, and entities do. Unlike them it reaches as far as the loaded chunks,
 * and it can soften: each ray goes to a random point of the light's disc ({@link #setSunRadius}), new every frame,
 * so the offline preset's sub-frames (or TAA) average a penumbra.
 *
 * <p>The trace runs inside renderpearl's own frame (RtDevice.executeInFrame), after the G-buffer and before the
 * lighting pass: renderpearl keeps every image in the general layout with full memory barriers between its
 * passes, so the G-buffer can be sampled directly; the result is written to a buffer and copied into a texture
 * renderpearl owns (its textures can't be storage images).
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
			    while (rayQueryProceedEXT(query)) {
			        if (rayQueryGetIntersectionTypeEXT(query, false) == gl_RayQueryCandidateIntersectionTriangleEXT
			                && blocks(query)) {
			            rayQueryConfirmIntersectionEXT(query);
			        }
			    }
			    bool shadowed = rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
			    pixels[index] = shadowed ? 0xFF000000u : 0xFFFFFFFFu;
			}
			""";

	private static Mode mode = Mode.OFF;
	/** The light's angular radius (degrees): the real sun's by default; 0 for hard shadows. */
	private static float sunRadius = 0.27f;
	private static boolean failed;
	private static String failure = "";
	private static boolean tracedThisFrame;
	private static long frameNumber;
	private static long traces;

	private static long setLayout;
	private static long pipelineLayout;
	private static long pipeline;
	private static long sampler;
	private static final List<Slot> SLOTS = new ArrayList<>();

	private static GpuTexture texture;
	private static GpuTextureView view;
	private static int width;
	private static int height;

	/** The resources of one trace, reused once the renderpearl submission that ran it has finished. */
	private static final class Slot {
		VkCommandBuffer commands;
		long pool;
		long set;
		RtBuffer pixels;
		long frameSubmission = -1;
	}

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
		return view;
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
		try {
			RtDevice rt = RtDevice.get();
			record(rt, frame, depth, normal, atlas, projection, modelView, towardsLight);
			tracedThisFrame = true;
			traces++;
		} catch (RuntimeException e) {
			failed = true;
			failure = e.getMessage();
			BedrockVisualsClient.LOGGER.error("[raytrace] ray-traced shadows failed; back to the shadow maps", e);
		}
	}

	private static void record(RtDevice rt, RtScene.Frame frame, GpuTextureView depth, GpuTextureView normal,
			GpuTextureView atlas, Matrix4fc projection, Matrix4fc modelView, Vector3fc towardsLight) {
		int w = depth.getWidth(0);
		int h = depth.getHeight(0);
		ensureTexture(w, h);
		ensurePipeline(rt);
		Slot slot = freeSlot(rt);
		long bytes = (long) w * h * 4;
		if (slot.pixels == null || slot.pixels.size() < bytes) {
			rt.free(slot.pixels);
			slot.pixels = rt.deviceBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
		}
		slot.frameSubmission = rt.frameSubmission();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR
					.calloc(stack).sType$Default().accelerationStructureCount(1)
					.pAccelerationStructures(stack.longs(frame.tlas()));
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(6, stack);
			writes.get(0).sType$Default().pNext(asWrite.address()).dstSet(slot.set).dstBinding(0).descriptorCount(1)
					.descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
			writes.get(1).sType$Default().dstSet(slot.set).dstBinding(1).descriptorCount(1)
					.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
							.buffer(frame.instances().buffer()).offset(0).range(VK_WHOLE_SIZE));
			image(writes.get(2), slot.set, 2, imageView(depth), stack);
			image(writes.get(3), slot.set, 3, imageView(normal), stack);
			image(writes.get(4), slot.set, 4, imageView(atlas), stack);
			writes.get(5).sType$Default().dstSet(slot.set).dstBinding(5).descriptorCount(1)
					.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
							.buffer(slot.pixels.buffer()).offset(0).range(bytes));
			vkUpdateDescriptorSets(rt.device, writes, null);

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

			long image = ((VulkanGpuTexture) texture).vkImage();
			rt.executeInFrame(slot.commands, commands -> {
				// renderpearl's own barrier between passes: everything before (the G-buffer) is done and visible.
				fullBarrier(commands, stack);
				vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
				vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
						stack.longs(slot.set), null);
				vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
				vkCmdDispatch(commands, (w + 7) / 8, (h + 7) / 8, 1);
				fullBarrier(commands, stack);
				VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack).bufferOffset(0).bufferRowLength(0)
						.bufferImageHeight(0);
				region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0)
						.layerCount(1);
				region.imageExtent().set(w, h, 1);
				vkCmdCopyBufferToImage(commands, slot.pixels.buffer(), image, VK_IMAGE_LAYOUT_GENERAL, region);
				fullBarrier(commands, stack);
			});
		}
	}

	private static void image(VkWriteDescriptorSet write, long set, int binding, long imageView, MemoryStack stack) {
		write.sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
						.sampler(sampler).imageView(imageView).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
	}

	private static long imageView(GpuTextureView view) {
		if (view instanceof VulkanGpuTextureView vulkan) {
			return vulkan.vkImageView();
		}
		throw new IllegalStateException("not a Vulkan texture view: " + view.getClass().getName());
	}

	private static void fullBarrier(VkCommandBuffer commands, MemoryStack stack) {
		RtScene.barrier(commands, stack, VK_ACCESS_MEMORY_WRITE_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
				VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
	}

	/** A slot whose last trace has finished, or a new one. */
	private static Slot freeSlot(RtDevice rt) {
		long done = rt.completedFrameSubmission();
		for (Slot slot : SLOTS) {
			if (slot.frameSubmission <= done) {
				return slot;
			}
		}
		Slot slot = new Slot();
		slot.commands = rt.allocateCommandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(3, stack);
			sizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
			sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
			sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(3);
			RtDevice.check(vkCreateDescriptorPool(rt.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(1).pPoolSizes(sizes), null, handle), "vkCreateDescriptorPool");
			slot.pool = handle.get(0);
			RtDevice.check(vkAllocateDescriptorSets(rt.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
					.descriptorPool(slot.pool).pSetLayouts(stack.longs(setLayout)), handle), "vkAllocateDescriptorSets");
			slot.set = handle.get(0);
		}
		SLOTS.add(slot);
		return slot;
	}

	private static void ensureTexture(int w, int h) {
		if (texture != null && width == w && height == h) {
			return;
		}
		if (texture != null) {
			view.close();
			texture.close();
		}
		texture = RenderSystem.getDevice().createTexture(() -> "Bedrock Visuals ray-traced shadows",
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
		if (!(texture instanceof VulkanGpuTexture)) {
			throw new IllegalStateException("not a Vulkan texture: " + texture.getClass().getName());
		}
		view = RenderSystem.getDevice().createTextureView(texture);
		width = w;
		height = h;
	}

	private static void ensurePipeline(RtDevice rt) {
		if (pipeline != 0) {
			return;
		}
		VkDevice device = rt.device;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(6, stack);
			int[] types = {VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
					VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
					VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
			for (int i = 0; i < types.length; i++) {
				bindings.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			RtDevice.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType$Default().pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
			setLayout = handle.get(0);
			VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
					.offset(0).size(112);
			RtDevice.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, handle), "vkCreatePipelineLayout");
			pipelineLayout = handle.get(0);

			ByteBuffer spirv = RayQuerySmokeTest.compile(SHADER);
			long module;
			try {
				RtDevice.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default()
						.pCode(spirv), null, handle), "vkCreateShaderModule");
				module = handle.get(0);
			} finally {
				MemoryUtil.memFree(spirv);
			}
			try {
				VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack);
				info.get(0).sType$Default().layout(pipelineLayout).stage().sType$Default()
						.stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
				RtDevice.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, handle),
						"vkCreateComputePipelines");
				pipeline = handle.get(0);
			} finally {
				vkDestroyShaderModule(device, module, null);
			}

			RtDevice.check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
					.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(VK_LOD_CLAMP_NONE), null, handle),
					"vkCreateSampler");
			sampler = handle.get(0);
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
