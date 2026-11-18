package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.*;

/**
 * Proves the ray queries work on this device, once, after the first frame: a bottom-level acceleration
 * structure holding one triangle (z = 0, around the origin), a top-level one holding it once, and a compute
 * shader casting four rays with rayQueryEXT: from z = -1 towards +z (a hit at t = 1), away from it (a miss),
 * past its side (a miss), and towards it again with the triangle's back face (a hit: culling is off). The
 * results are read back and logged; nothing is kept.
 */
public final class RayQuerySmokeTest {
	private static final String SHADER = """
			#version 460
			#extension GL_EXT_ray_query : require
			layout(local_size_x = 4) in;
			layout(set = 0, binding = 0) uniform accelerationStructureEXT scene;
			layout(set = 0, binding = 1, std430) buffer Result { uint hits[4]; float distances[4]; };
			void main() {
			    uint i = gl_LocalInvocationID.x;
			    vec3 origins[4] = vec3[](vec3(0, 0, -1), vec3(0, 0, -1), vec3(5, 0, -1), vec3(0, 0, 1));
			    vec3 directions[4] = vec3[](vec3(0, 0, 1), vec3(0, 0, -1), vec3(0, 0, 1), vec3(0, 0, -1));
			    rayQueryEXT query;
			    rayQueryInitializeEXT(query, scene, gl_RayFlagsOpaqueEXT, 0xFF, origins[i], 0.001, directions[i], 100.0);
			    while (rayQueryProceedEXT(query)) {
			    }
			    bool hit = rayQueryGetIntersectionTypeEXT(query, true) == gl_RayQueryCommittedIntersectionTriangleEXT;
			    hits[i] = hit ? 1u : 0u;
			    distances[i] = hit ? rayQueryGetIntersectionTEXT(query, true) : -1.0;
			}
			""";
	private static final int[] EXPECTED_HITS = {1, 0, 0, 1};
	/** The same bindings with no ray query: whether a plain dispatch through this path writes at all. */
	private static final String PLAIN_SHADER = """
			#version 460
			layout(local_size_x = 4) in;
			layout(set = 0, binding = 1, std430) buffer Result { uint hits[4]; float distances[4]; };
			void main() {
			    uint i = gl_LocalInvocationID.x;
			    hits[i] = 40u + i;
			    distances[i] = 0.5;
			}
			""";

	private static String result = "not run yet";
	private static boolean ran;

	private RayQuerySmokeTest() {
	}

	public static String status() {
		return "smoke test " + result;
	}

	/** Runs it again on the next runOnce (ScriptRunner's raytest line). */
	public static void rerun() {
		ran = false;
	}

	/** Once, on the render thread between frames (a client tick). */
	public static void runOnce() {
		if (ran) {
			return;
		}
		ran = true;
		if (!RayQuery.available()) {
			result = "skipped (no ray queries)";
			BedrockVisualsClient.LOGGER.info("[raytrace] ray queries not available on this device: {}", RayQuery.status());
			return;
		}
		long start = System.nanoTime();
		try {
			result = run(RayQuery.device());
		} catch (RuntimeException e) {
			result = "FAILED (" + e.getMessage() + ")";
			BedrockVisualsClient.LOGGER.error("[raytrace] ray query smoke test failed", e);
			return;
		}
		BedrockVisualsClient.LOGGER.info("[raytrace] ray query smoke test {} in {} ms", result,
				(System.nanoTime() - start) / 1_000_000);
	}

	/** Everything created here, destroyed in reverse at the end. */
	private static final class Resources {
		final VkDevice device;
		final List<Runnable> cleanup = new ArrayList<>();

		Resources(VkDevice device) {
			this.device = device;
		}

		void close() {
			for (int i = cleanup.size() - 1; i >= 0; i--) {
				cleanup.get(i).run();
			}
		}
	}

	private record Buffer(long buffer, long memory, long address, long size) {
	}

	private static String run(VulkanDevice vulkan) {
		VkDevice device = vulkan.vkDevice();
		Resources res = new Resources(device);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
			vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memory);
			VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack)
					.sType$Default();
			VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(asProps.address());
			vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props);
			long scratchAlignment = Math.max(asProps.minAccelerationStructureScratchOffsetAlignment(), 1);
			String gpu = props.properties().deviceNameString();

			int hostFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
			int inputUsage = VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
					| VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
			Buffer vertices = buffer(res, memory, 9 * 4, inputUsage, hostFlags);
			write(res, vertices, b -> b.putFloat(-1).putFloat(-1).putFloat(0).putFloat(1).putFloat(-1).putFloat(0)
					.putFloat(0).putFloat(1).putFloat(0));
			Buffer indices = buffer(res, memory, 3 * 4, inputUsage, hostFlags);
			write(res, indices, b -> b.putInt(0).putInt(1).putInt(2));

			// The bottom level: the triangle.
			VkAccelerationStructureGeometryKHR.Buffer blasGeometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			blasGeometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
			blasGeometry.get(0).geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
					.vertexStride(12).maxVertex(2).indexType(VK_INDEX_TYPE_UINT32);
			blasGeometry.get(0).geometry().triangles().vertexData().deviceAddress(vertices.address());
			blasGeometry.get(0).geometry().triangles().indexData().deviceAddress(indices.address());
			long blas = buildAccelerationStructure(res, memory, vulkan, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
					blasGeometry, 1, scratchAlignment, stack);

			// The top level: the triangle once, untransformed. VkAccelerationStructureInstanceKHR: a 3x4 transform,
			// custom index (24 bits) and mask (8), shader binding table offset (24) and flags (8), the bottom
			// level's address.
			long blasAddress = vkGetAccelerationStructureDeviceAddressKHR(device,
					VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(blas));
			Buffer instance = buffer(res, memory, 64, inputUsage, hostFlags);
			write(res, instance, b -> {
				float[] identity = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};
				for (float f : identity) {
					b.putFloat(f);
				}
				b.putInt(0xFF << 24);
				b.putInt(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
				b.putLong(blasAddress);
			});
			VkAccelerationStructureGeometryKHR.Buffer tlasGeometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			tlasGeometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
			tlasGeometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
			tlasGeometry.get(0).geometry().instances().data().deviceAddress(instance.address());
			long tlas = buildAccelerationStructure(res, memory, vulkan, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
					tlasGeometry, 1, scratchAlignment, stack);

			// The rays.
			Buffer results = buffer(res, memory, 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags);
			// A pattern the shader overwrites: still there afterwards, the dispatch never ran.
			write(res, results, b -> {
				for (int i = 0; i < 8; i++) {
					b.putInt(99);
				}
			});
			trace(res, vulkan, tlas, results, stack, PLAIN_SHADER);
			int[] plain = new int[1];
			read(res, results, b -> plain[0] = b.getInt(0));
			BedrockVisualsClient.LOGGER.info("[raytrace] plain dispatch wrote {} (40 expected)", plain[0]);
			trace(res, vulkan, tlas, results, stack, SHADER);

			int[] hits = new int[4];
			float[] distances = new float[4];
			read(res, results, b -> {
				for (int i = 0; i < 4; i++) {
					hits[i] = b.getInt(i * 4);
					distances[i] = b.getFloat(16 + i * 4);
				}
			});
			boolean ok = java.util.Arrays.equals(hits, EXPECTED_HITS) && Math.abs(distances[0] - 1) < 1e-4f
					&& Math.abs(distances[3] - 1) < 1e-4f;
			return String.format(Locale.ROOT, "%s on %s: hits %s, distances %s", ok ? "passed" : "WRONG RESULTS", gpu,
					java.util.Arrays.toString(hits), java.util.Arrays.toString(distances));
		} finally {
			vkDeviceWaitIdle(device);
			res.close();
		}
	}

	private static long buildAccelerationStructure(Resources res, VkPhysicalDeviceMemoryProperties memory,
			VulkanDevice vulkan, int type, VkAccelerationStructureGeometryKHR.Buffer geometry, int primitives,
			long scratchAlignment, MemoryStack stack) {
		VkDevice device = res.device;
		VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
		info.get(0).sType$Default().type(type).flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
				.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(geometry.remaining()).pGeometries(geometry);
		VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
		vkGetAccelerationStructureBuildSizesKHR(device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0),
				stack.ints(primitives), sizes);
		Buffer storage = buffer(res, memory, sizes.accelerationStructureSize(),
				VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
				VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		LongBuffer handle = stack.mallocLong(1);
		check(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
				.buffer(storage.buffer()).size(sizes.accelerationStructureSize()).type(type), null, handle),
				"vkCreateAccelerationStructureKHR");
		long structure = handle.get(0);
		res.cleanup.add(() -> vkDestroyAccelerationStructureKHR(device, structure, null));
		Buffer scratch = buffer(res, memory, sizes.buildScratchSize() + scratchAlignment,
				VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT,
				VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
		long scratchAddress = (scratch.address() + scratchAlignment - 1) / scratchAlignment * scratchAlignment;
		info.get(0).dstAccelerationStructure(structure);
		info.get(0).scratchData().deviceAddress(scratchAddress);
		VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
				.primitiveCount(primitives);
		PointerBuffer ranges = stack.pointers(range.address());
		submit(res, vulkan, stack, cmd -> {
			vkCmdBuildAccelerationStructuresKHR(cmd, info, ranges);
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
					.srcAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
					.dstAccessMask(VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
					VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
					barrier, null, null);
		});
		return structure;
	}

	private static void trace(Resources res, VulkanDevice vulkan, long tlas, Buffer results, MemoryStack stack,
			String source) {
		VkDevice device = res.device;
		LongBuffer handle = stack.mallocLong(1);
		ByteBuffer spirv = compile(source);
		try {
			check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null,
					handle), "vkCreateShaderModule");
		} finally {
			MemoryUtil.memFree(spirv);
		}
		long module = handle.get(0);
		res.cleanup.add(() -> vkDestroyShaderModule(device, module, null));

		VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
		bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1)
				.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
		bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1)
				.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
		check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
				.pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
		long setLayout = handle.get(0);
		res.cleanup.add(() -> vkDestroyDescriptorSetLayout(device, setLayout, null));
		check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
				.pSetLayouts(stack.longs(setLayout)), null, handle), "vkCreatePipelineLayout");
		long pipelineLayout = handle.get(0);
		res.cleanup.add(() -> vkDestroyPipelineLayout(device, pipelineLayout, null));
		VkComputePipelineCreateInfo.Buffer pipelineInfo = VkComputePipelineCreateInfo.calloc(1, stack);
		pipelineInfo.get(0).sType$Default().layout(pipelineLayout).stage().sType$Default()
				.stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
		check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelineInfo, null, handle), "vkCreateComputePipelines");
		long pipeline = handle.get(0);
		res.cleanup.add(() -> vkDestroyPipeline(device, pipeline, null));

		VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
		poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
		poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
		check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1)
				.pPoolSizes(poolSizes), null, handle), "vkCreateDescriptorPool");
		long pool = handle.get(0);
		res.cleanup.add(() -> vkDestroyDescriptorPool(device, pool, null));
		check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
				.descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), handle), "vkAllocateDescriptorSets");
		long set = handle.get(0);

		VkWriteDescriptorSetAccelerationStructureKHR asWrite = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
				.sType$Default().accelerationStructureCount(1).pAccelerationStructures(stack.longs(tlas));
		VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
		writes.get(0).sType$Default().pNext(asWrite.address()).dstSet(set).dstBinding(0).descriptorCount(1)
				.descriptorType(VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR);
		writes.get(1).sType$Default().dstSet(set).dstBinding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
				.descriptorCount(1).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(results.buffer()).offset(0).range(results.size()));
		vkUpdateDescriptorSets(device, writes, null);

		submit(res, vulkan, stack, cmd -> {
			vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
			vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
			vkCmdDispatch(cmd, 1, 1, 1);
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default()
					.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
			vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null,
					null);
		});
	}

	/** GLSL to SPIR-V for Vulkan 1.2 (shaderc, as the game itself ships it). The caller frees the result. */
	static ByteBuffer compile(String source) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
					Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_target_spirv(options, Shaderc.shaderc_spirv_version_1_5);
			long compiled = Shaderc.shaderc_compile_into_spv(compiler, source, Shaderc.shaderc_compute_shader,
					"ray query smoke test", "main", options);
			try {
				if (Shaderc.shaderc_result_get_compilation_status(compiled) != Shaderc.shaderc_compilation_status_success) {
					throw new IllegalStateException("shader compile failed: " + Shaderc.shaderc_result_get_error_message(compiled));
				}
				ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(compiled);
				ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
				copy.put(bytes).flip();
				return copy;
			} finally {
				Shaderc.shaderc_result_release(compiled);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	/** Records with a one-time command buffer on the graphics queue and waits for it. */
	private static void submit(Resources res, VulkanDevice vulkan, MemoryStack stack,
			java.util.function.Consumer<VkCommandBuffer> record) {
		VkDevice device = res.device;
		LongBuffer handle = stack.mallocLong(1);
		check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
				.flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT).queueFamilyIndex(vulkan.graphicsQueue().queueFamilyIndex()),
				null, handle), "vkCreateCommandPool");
		long pool = handle.get(0);
		try {
			PointerBuffer pointer = stack.mallocPointer(1);
			check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
					.commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer),
					"vkAllocateCommandBuffers");
			VkCommandBuffer cmd = new VkCommandBuffer(pointer.get(0), device);
			check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
					.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
			record.accept(cmd);
			check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
			check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateFence");
			long fence = handle.get(0);
			try {
				check(vkQueueSubmit(vulkan.graphicsQueue().vkQueue(), VkSubmitInfo.calloc(stack).sType$Default()
						.pCommandBuffers(stack.pointers(cmd)), fence), "vkQueueSubmit");
				check(vkWaitForFences(device, stack.longs(fence), true, 10_000_000_000L), "vkWaitForFences");
			} finally {
				vkDestroyFence(device, fence, null);
			}
		} finally {
			vkDestroyCommandPool(device, pool, null);
		}
	}

	private static Buffer buffer(Resources res, VkPhysicalDeviceMemoryProperties memory, long size, int usage,
			int properties) {
		VkDevice device = res.device;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);
			check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage)
					.sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "vkCreateBuffer");
			long buffer = handle.get(0);
			res.cleanup.add(() -> vkDestroyBuffer(device, buffer, null));
			VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
			vkGetBufferMemoryRequirements(device, buffer, requirements);
			int type = memoryType(memory, requirements.memoryTypeBits(), properties);
			boolean addressable = (usage & VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT) != 0;
			VkMemoryAllocateFlagsInfo flags = VkMemoryAllocateFlagsInfo.calloc(stack).sType$Default()
					.flags(VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
			check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
					.pNext(addressable ? flags.address() : 0).allocationSize(requirements.size()).memoryTypeIndex(type),
					null, handle), "vkAllocateMemory");
			long allocation = handle.get(0);
			res.cleanup.add(() -> vkFreeMemory(device, allocation, null));
			check(vkBindBufferMemory(device, buffer, allocation, 0), "vkBindBufferMemory");
			long address = addressable ? vkGetBufferDeviceAddress(device,
					VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer)) : 0;
			return new Buffer(buffer, allocation, address, size);
		}
	}

	private static int memoryType(VkPhysicalDeviceMemoryProperties memory, int bits, int properties) {
		for (int i = 0; i < memory.memoryTypeCount(); i++) {
			if ((bits & (1 << i)) != 0 && (memory.memoryTypes(i).propertyFlags() & properties) == properties) {
				return i;
			}
		}
		throw new IllegalStateException("no memory type with properties " + properties);
	}

	private static void write(Resources res, Buffer buffer, java.util.function.Consumer<ByteBuffer> writer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer mapped = stack.mallocPointer(1);
			check(vkMapMemory(res.device, buffer.memory(), 0, buffer.size(), 0, mapped), "vkMapMemory");
			writer.accept(MemoryUtil.memByteBuffer(mapped.get(0), (int) buffer.size()).order(java.nio.ByteOrder.nativeOrder()));
			vkUnmapMemory(res.device, buffer.memory());
		}
	}

	private static void read(Resources res, Buffer buffer, java.util.function.Consumer<ByteBuffer> reader) {
		write(res, buffer, reader);
	}

	private static void check(int result, String call) {
		if (result != VK_SUCCESS) {
			throw new IllegalStateException(call + " returned " + result);
		}
	}
}
