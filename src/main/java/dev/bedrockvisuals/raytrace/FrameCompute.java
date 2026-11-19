package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * A full-screen compute pass of the ray tracing's, recorded into renderpearl's own frame (RtDevice.executeInFrame)
 * between its passes, whose result lands in a renderpearl texture for its shaders to sample.
 *
 * <p>renderpearl keeps every image in the general layout and puts full memory barriers between its passes, so the
 * pass samples renderpearl's textures directly (combined image samplers, nearest). Its textures can't be storage
 * images, so the shader writes a buffer (the last binding, {@code bytesPerPixel} a pixel, rows packed) that is
 * copied into {@link #view()}'s texture.
 *
 * <p>A pass may have several stages (shaders over the same bindings and push constants), dispatched in order with
 * a full barrier between them; each stage has its own dispatch size. The last stage writes the output.
 *
 * <p>Each dispatch gets a slot (command buffer, descriptor set, output buffer), reused once the renderpearl
 * submission it ran in has finished: the offline preset dispatches several times in one submission.
 */
final class FrameCompute {
	/** A binding's content, written into the slot's descriptor set. */
	sealed interface Binding {
	}

	record Structure(long handle) implements Binding {
	}

	record Buffer(long buffer) implements Binding {
	}

	record Image(GpuTextureView view) implements Binding {
	}

	private final String name;
	private final String[] sources;
	private final int[] types;
	private final int pushSize;
	private final int bytesPerPixel;
	private final GpuFormat format;

	private long setLayout;
	private long pipelineLayout;
	private long[] pipelines;
	private long sampler;
	private final List<Slot> slots = new ArrayList<>();
	private GpuTexture texture;
	private GpuTextureView view;
	private int width;
	private int height;

	private static final class Slot {
		VkCommandBuffer commands;
		long pool;
		long set;
		RtBuffer output;
		long frameSubmission = -1;
	}

	/**
	 * @param types the descriptor types of bindings 0 to n-1; binding n is the output buffer
	 * @param format the output texture's format, {@code bytesPerPixel} a texel
	 */
	FrameCompute(String name, String source, int[] types, int pushSize, int bytesPerPixel, GpuFormat format) {
		this(name, new String[] {source}, types, pushSize, bytesPerPixel, format);
	}

	/** A pass of several stages, one shader each (see the class comment). */
	FrameCompute(String name, String[] sources, int[] types, int pushSize, int bytesPerPixel, GpuFormat format) {
		this.name = name;
		this.sources = sources;
		this.types = types;
		this.pushSize = pushSize;
		this.bytesPerPixel = bytesPerPixel;
		this.format = format;
	}

	GpuTextureView view() {
		return view;
	}

	/** Dispatches one thread a pixel (8x8 groups) over w x h and copies the result into {@link #view()}. */
	void dispatch(RtDevice rt, int w, int h, List<Binding> bindings, ByteBuffer pushConstants) {
		dispatch(rt, w, h, bindings, pushConstants, new int[][] {{(w + 7) / 8, (h + 7) / 8, 1}});
	}

	/**
	 * Dispatches each stage with its own group counts ({@code groups[stage]}: x, y, z), in order, and copies the
	 * output (w x h) into {@link #view()}.
	 */
	void dispatch(RtDevice rt, int w, int h, List<Binding> bindings, ByteBuffer pushConstants, int[][] groups) {
		ensureTexture(w, h);
		ensurePipeline(rt);
		Slot slot = freeSlot(rt);
		long bytes = (long) w * h * bytesPerPixel;
		if (slot.output == null || slot.output.size() < bytes) {
			rt.free(slot.output);
			slot.output = rt.deviceBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
		}
		slot.frameSubmission = rt.frameSubmission();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(types.length + 1, stack);
			for (int i = 0; i < types.length; i++) {
				VkWriteDescriptorSet write = writes.get(i).sType$Default().dstSet(slot.set).dstBinding(i)
						.descriptorCount(1).descriptorType(types[i]);
				switch (bindings.get(i)) {
					case Structure structure -> write.pNext(VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
							.sType$Default().accelerationStructureCount(1)
							.pAccelerationStructures(stack.longs(structure.handle())).address());
					case Buffer buffer -> write.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
							.buffer(buffer.buffer()).offset(0).range(VK_WHOLE_SIZE));
					case Image image -> write.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).sampler(sampler)
							.imageView(imageView(image.view())).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
				}
			}
			writes.get(types.length).sType$Default().dstSet(slot.set).dstBinding(types.length).descriptorCount(1)
					.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack)
							.buffer(slot.output.buffer()).offset(0).range(bytes));
			vkUpdateDescriptorSets(rt.device, writes, null);

			long image = ((VulkanGpuTexture) texture).vkImage();
			rt.executeInFrame(slot.commands, commands -> {
				// As renderpearl's own barrier between passes: what came before is done and visible.
				fullBarrier(commands, stack);
				vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0,
						stack.longs(slot.set), null);
				vkCmdPushConstants(commands, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstants);
				for (int stage = 0; stage < pipelines.length; stage++) {
					vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[stage]);
					vkCmdDispatch(commands, groups[stage][0], groups[stage][1], groups[stage][2]);
					fullBarrier(commands, stack);
				}
				VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
				region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0)
						.layerCount(1);
				region.imageExtent().set(w, h, 1);
				vkCmdCopyBufferToImage(commands, slot.output.buffer(), image, VK_IMAGE_LAYOUT_GENERAL, region);
				fullBarrier(commands, stack);
			});
		}
	}

	private static long imageView(GpuTextureView view) {
		if (view instanceof VulkanGpuTextureView vulkan) {
			return vulkan.vkImageView();
		}
		throw new IllegalStateException("not a Vulkan texture view: " + view.getClass().getName());
	}

	private static void fullBarrier(VkCommandBuffer commands, MemoryStack stack) {
		RtScene.barrier(commands, stack, VK_ACCESS_MEMORY_WRITE_BIT,
				VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
				VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
	}

	private Slot freeSlot(RtDevice rt) {
		long done = rt.completedFrameSubmission();
		for (Slot slot : slots) {
			if (slot.frameSubmission <= done) {
				return slot;
			}
		}
		Slot slot = new Slot();
		slot.commands = rt.allocateCommandBuffer();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);
			VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(types.length + 1, stack);
			for (int i = 0; i < types.length; i++) {
				sizes.get(i).type(types[i]).descriptorCount(1);
			}
			sizes.get(types.length).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
			RtDevice.check(vkCreateDescriptorPool(rt.device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
					.maxSets(1).pPoolSizes(sizes), null, handle), "vkCreateDescriptorPool");
			slot.pool = handle.get(0);
			RtDevice.check(vkAllocateDescriptorSets(rt.device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
					.descriptorPool(slot.pool).pSetLayouts(stack.longs(setLayout)), handle), "vkAllocateDescriptorSets");
			slot.set = handle.get(0);
		}
		slots.add(slot);
		return slot;
	}

	private void ensureTexture(int w, int h) {
		if (texture != null && width == w && height == h) {
			return;
		}
		if (texture != null) {
			view.close();
			texture.close();
		}
		texture = RenderSystem.getDevice().createTexture(() -> "Bedrock Visuals " + name,
				GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, format, w, h, 1, 1);
		if (!(texture instanceof VulkanGpuTexture)) {
			throw new IllegalStateException("not a Vulkan texture: " + texture.getClass().getName());
		}
		view = RenderSystem.getDevice().createTextureView(texture);
		width = w;
		height = h;
	}

	private void ensurePipeline(RtDevice rt) {
		if (pipelines != null) {
			return;
		}
		VkDevice device = rt.device;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);
			VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(types.length + 1, stack);
			for (int i = 0; i <= types.length; i++) {
				bindings.get(i).binding(i).descriptorType(i < types.length ? types[i] : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
						.descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
			}
			RtDevice.check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
					.sType$Default().pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
			setLayout = handle.get(0);
			VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
					.offset(0).size(pushSize);
			RtDevice.check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
					.pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, handle), "vkCreatePipelineLayout");
			pipelineLayout = handle.get(0);

			long[] built = new long[sources.length];
			for (int stage = 0; stage < sources.length; stage++) {
				ByteBuffer spirv = RayQuerySmokeTest.compile(sources[stage]);
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
					built[stage] = handle.get(0);
				} finally {
					vkDestroyShaderModule(device, module, null);
				}
			}
			pipelines = built;

			RtDevice.check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
					.magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST).mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
					.addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
					.addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(VK_LOD_CLAMP_NONE), null, handle),
					"vkCreateSampler");
			sampler = handle.get(0);
		}
	}
}
