package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.blaze3d.platform.NativeImage;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.render.LightingState;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.file.Path;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * A picture of the ray-traced scene from the camera, for checking it against the game's own: every pixel casts a
 * ray through the top level, and the hit is shaded from the hit's own vertices (read through the instance table,
 * as later lighting passes will), lit by the sun with a ray-traced shadow. Terrain is grey (Sodium's vertex colour:
 * biome tint and ambient occlusion), cutout geometry green-tinted, translucent blue-tinted, entities red-tinted;
 * a miss is sky blue. Saved as a PNG (ScriptRunner's {@code rtview} line).
 */
public final class RtDebugView {
	private static final int WIDTH = 960;
	private static final int HEIGHT = 540;
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
			layout(set = 0, binding = 2, std430) writeonly buffer Image { uint pixels[]; };
			layout(buffer_reference, scalar, buffer_reference_align = 4) readonly buffer Words { uint w[]; };
			layout(push_constant) uniform View { mat4 inverseViewProjection; vec4 camera; vec4 sun; uvec2 size; };

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

			bool shadowed(vec3 origin, vec3 direction) {
			    rayQueryEXT query;
			    rayQueryInitializeEXT(query, scene, gl_RayFlagsTerminateOnFirstHitEXT, 0xFF, origin, 0.01, direction, 512.0);
			    while (rayQueryProceedEXT(query)) {
			        rayQueryConfirmIntersectionEXT(query);
			    }
			    return rayQueryGetIntersectionTypeEXT(query, true) != gl_RayQueryCommittedIntersectionNoneEXT;
			}

			void main() {
			    uvec2 pixel = gl_GlobalInvocationID.xy;
			    if (any(greaterThanEqual(pixel, size))) {
			        return;
			    }
			    vec2 ndc = (vec2(pixel) + 0.5) / vec2(size) * 2.0 - 1.0;
			    ndc.y = -ndc.y;
			    vec4 far = inverseViewProjection * vec4(ndc, 0.5, 1.0);
			    vec3 direction = normalize(far.xyz / far.w);
			    vec3 origin = camera.xyz;

			    rayQueryEXT query;
			    rayQueryInitializeEXT(query, scene, gl_RayFlagsNoneEXT, 0xFF, origin, 0.05, direction, 1024.0);
			    while (rayQueryProceedEXT(query)) {
			        rayQueryConfirmIntersectionEXT(query);
			    }
			    vec3 color = vec3(0.55, 0.7, 0.95);
			    if (rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionTriangleEXT) {
			        InstanceInfo info = instances[rayQueryGetIntersectionInstanceCustomIndexEXT(query, true)];
			        uint geometry = uint(rayQueryGetIntersectionGeometryIndexEXT(query, true));
			        uint primitive = uint(rayQueryGetIntersectionPrimitiveIndexEXT(query, true));
			        uint quad = primitive >> 1u;
			        uvec3 corners = (primitive & 1u) == 0u ? uvec3(0u, 1u, 2u) : uvec3(2u, 3u, 0u);
			        Words words = Words(info.vertices);
			        vec3 a, b, c;
			        uint first;
			        vec3 tint;
			        if (info.kind == 0u) {
			            uint pass = (info.passes >> (2u * geometry)) & 3u;
			            first = info.firstVertex[geometry] + quad * 4u;
			            a = sodiumPosition(words, first + corners.x);
			            b = sodiumPosition(words, first + corners.y);
			            c = sodiumPosition(words, first + corners.z);
			            vec3 vertexColor = unpackUnorm4x8(words.w[first * 5u + 2u]).rgb;
			            tint = vertexColor * (pass == 0u ? vec3(1.0) : pass == 1u ? vec3(0.6, 1.0, 0.6) : vec3(0.5, 0.7, 1.0));
			        } else {
			            first = quad * 4u;
			            a = entityPosition(words, first + corners.x);
			            b = entityPosition(words, first + corners.y);
			            c = entityPosition(words, first + corners.z);
			            tint = unpackUnorm4x8(words.w[first * 9u + 3u]).rgb * vec3(1.0, 0.55, 0.5);
			        }
			        vec3 normal = normalize(cross(b - a, c - a));
			        if (dot(normal, direction) > 0.0) {
			            normal = -normal;
			        }
			        vec3 hit = origin + direction * rayQueryGetIntersectionTEXT(query, true);
			        float light = max(dot(normal, sun.xyz), 0.0);
			        if (light > 0.0 && shadowed(hit + normal * 0.01, sun.xyz)) {
			            light = 0.0;
			        }
			        color = tint * (0.3 + 0.7 * light);
			    }
			    pixels[pixel.y * size.x + pixel.x] = packUnorm4x8(vec4(pow(color, vec3(1.0 / 2.2)), 1.0));
			}
			""";

	private static Path requested;

	private RtDebugView() {
	}

	/** Saves the next frame's picture of the scene to {@code file} (turning the scene on if it is off). */
	public static void request(Path file) {
		requested = file;
		RtScene.setEnabled(true);
	}

	static void afterScene(RtScene.Frame frame) {
		if (requested == null || frame == null) {
			return;
		}
		Path file = requested;
		requested = null;
		long start = System.nanoTime();
		try {
			render(RtDevice.get(), frame, file);
			BedrockVisualsClient.LOGGER.info("[raytrace] scene view saved to {} in {} ms ({})", file,
					(System.nanoTime() - start) / 1_000_000, RtScene.status());
		} catch (RuntimeException e) {
			BedrockVisualsClient.LOGGER.error("[raytrace] scene view failed", e);
		}
	}

	private static void render(RtDevice rt, RtScene.Frame frame, Path file) {
		VkDevice device = rt.device;
		java.util.List<Runnable> cleanup = new java.util.ArrayList<>();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);
			ByteBuffer spirv = RayQuerySmokeTest.compile(SHADER);
			try {
				RtDevice.check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default()
						.pCode(spirv), null, handle), "vkCreateShaderModule");
			} finally {
				MemoryUtil.memFree(spirv);
			}
			long module = handle.get(0);
			cleanup.add(() -> vkDestroyShaderModule(device, module, null));

			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(3, stack);
			bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
					.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			RtDevice.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType$Default().pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
			long setLayout = handle.get(0);
			cleanup.add(() -> vkDestroyDescriptorSetLayout(device, setLayout, null));
			VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
					.offset(0).size(112);
			RtDevice.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, handle), "vkCreatePipelineLayout");
			long pipelineLayout = handle.get(0);
			cleanup.add(() -> vkDestroyPipelineLayout(device, pipelineLayout, null));
			VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
			pipelineInfo.get(0).sType$Default().layout(pipelineLayout).stage().sType$Default()
					.stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
			RtDevice.check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, handle),
					"vkCreateComputePipelines");
			long pipeline = handle.get(0);
			cleanup.add(() -> vkDestroyPipeline(device, pipeline, null));

			VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
			poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
			poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
			RtDevice.check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(1).pPoolSizes(poolSizes), null, handle), "vkCreateDescriptorPool");
			long pool = handle.get(0);
			cleanup.add(() -> vkDestroyDescriptorPool(device, pool, null));
			RtDevice.check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
					.descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), handle), "vkAllocateDescriptorSets");
			long set = handle.get(0);

			RtBuffer pixels = rt.hostBuffer((long) WIDTH * HEIGHT * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
			cleanup.add(() -> rt.free(pixels));
			VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR
					.calloc(stack).sType$Default().accelerationStructureCount(1)
					.pAccelerationStructures(stack.longs(frame.tlas()));
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);
			writes.get(0).sType$Default().pNext(asWrite.address()).dstSet(set).dstBinding(0).descriptorCount(1)
					.descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
			writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
					.descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
							.buffer(frame.instances().buffer()).offset(0).range(VK_WHOLE_SIZE));
			writes.get(2).sType$Default().dstSet(set).dstBinding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
					.descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
							.buffer(pixels.buffer()).offset(0).range(VK_WHOLE_SIZE));
			vkUpdateDescriptorSets(device, writes, null);

			// The terrain's own projection and view rotation (Sodium's, as the G-buffer drew it: positions are
			// relative to the camera); rays start at the camera, relative to the scene's origin.
			Matrix4f inverse = new Matrix4f(dev.bedrockvisuals.render.GBufferPass.projection())
					.mul(dev.bedrockvisuals.render.GBufferPass.modelView()).invert();
			Vector3f sun = new Vector3f(LightingState.current().sunDirection());
			if (sun.y < 0) {
				sun.negate(); // the moon
			}
			ByteBuffer constants = stack.malloc(112);
			inverse.get(0, constants);
			constants.putFloat(64, (float) (frame.cameraX() - frame.originX()));
			constants.putFloat(68, (float) (frame.cameraY() - frame.originY()));
			constants.putFloat(72, (float) (frame.cameraZ() - frame.originZ()));
			constants.putFloat(76, 0);
			constants.putFloat(80, sun.x).putFloat(84, sun.y).putFloat(88, sun.z).putFloat(92, 0);
			constants.putInt(96, WIDTH).putInt(100, HEIGHT).putInt(104, 0).putInt(108, 0);

			long serial = rt.submit(commands -> {
				vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
				vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
				vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, constants);
				vkCmdDispatch(commands, (WIDTH + 7) / 8, (HEIGHT + 7) / 8, 1);
				RtScene.barrier(commands, stack, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT,
						VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT);
			});
			rt.waitFor(serial);

			ByteBuffer data = pixels.bytes();
			try (NativeImage image = new NativeImage(WIDTH, HEIGHT, false)) {
				for (int y = 0; y < HEIGHT; y++) {
					for (int x = 0; x < WIDTH; x++) {
						int rgba = data.getInt((y * WIDTH + x) * 4);
						// packUnorm4x8: red in the low byte; NativeImage wants ARGB.
						int r = rgba & 0xFF;
						int g = rgba >>> 8 & 0xFF;
						int b = rgba >>> 16 & 0xFF;
						image.setPixel(x, y, 0xFF000000 | r << 16 | g << 8 | b);
					}
				}
				image.writeToFile(file);
			} catch (java.io.IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		} finally {
			for (int i = cleanup.size() - 1; i >= 0; i--) {
				cleanup.get(i).run();
			}
		}
	}
}
