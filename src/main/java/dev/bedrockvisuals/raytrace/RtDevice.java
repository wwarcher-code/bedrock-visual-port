package dev.bedrockvisuals.raytrace;

import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.bedrockvisuals.mixin.renderpearl.VulkanCommandEncoderAccessor;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.function.Consumer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.*;

/**
 * The ray tracing's own Vulkan resources on Minecraft's device, beside renderpearl's: a memory allocator made with
 * buffer device addresses (renderpearl's isn't), command buffers submitted to the game's graphics queue on the
 * render thread, and deletion deferred until the GPU is done with what is deleted.
 *
 * <p>Every submission gets a serial and a fence. Something deleted waits for the submission that was next when it
 * was deleted (and every one before it) to finish. Commands the ray tracing records into renderpearl's own frame
 * ({@link #executeInFrame}) use its resources too, so something deleted also waits for the renderpearl submission
 * being recorded when it was deleted (renderpearl signals a timeline semaphore with each submission's index).
 */
public final class RtDevice {
	/** At most this many submissions in flight before {@link #submit} waits for the oldest. */
	private static final int MAX_IN_FLIGHT = 8;

	private static RtDevice instance;

	final VkDevice device;
	final VkQueue queue;
	final long allocator;
	final long scratchAlignment;
	final String gpuName;
	private final long commandPool;
	private final ArrayDeque<InFlight> inFlight = new ArrayDeque<>();
	private final ArrayDeque<VkCommandBuffer> freeCommandBuffers = new ArrayDeque<>();
	private final ArrayDeque<Long> freeFences = new ArrayDeque<>();
	private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
	/** The serial the next submission gets. */
	private long nextSerial = 1;
	/** Every submission up to this serial has finished. */
	private long completedSerial;

	private record InFlight(long serial, VkCommandBuffer commands, long fence) {
	}

	private record Deferred(long serial, long frameSubmission, Runnable free) {
	}

	private final VulkanCommandEncoder encoder;
	private final long frameSemaphore;

	private RtDevice(VulkanDevice vulkan) {
		device = vulkan.vkDevice();
		queue = vulkan.graphicsQueue().vkQueue();
		encoder = vulkan.createCommandEncoder();
		frameSemaphore = ((VulkanCommandEncoderAccessor) (Object) encoder).bedrockvisuals$submitSemaphore();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps = VkPhysicalDeviceAccelerationStructurePropertiesKHR
					.calloc(stack).sType$Default();
			VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
					.pNext(asProps.address());
			vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), props);
			scratchAlignment = Math.max(asProps.minAccelerationStructureScratchOffsetAlignment(), 1);
			gpuName = props.properties().deviceNameString();

			VkInstance vkInstance = device.getPhysicalDevice().getInstance();
			VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(vkInstance, device);
			PointerBuffer handle = stack.mallocPointer(1);
			check(vmaCreateAllocator(VmaAllocatorCreateInfo.calloc(stack)
					.flags(VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
					.physicalDevice(device.getPhysicalDevice()).device(device).instance(vkInstance)
					.pVulkanFunctions(functions).vulkanApiVersion(VK_API_VERSION_1_2), handle), "vmaCreateAllocator");
			allocator = handle.get(0);

			LongBuffer pool = stack.mallocLong(1);
			check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
					.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
					.queueFamilyIndex(vulkan.graphicsQueue().queueFamilyIndex()), null, pool), "vkCreateCommandPool");
			commandPool = pool.get(0);
		}
	}

	/** The ray tracing's device, made on first use; null where there are no ray queries. */
	public static RtDevice get() {
		if (instance == null && RayQuery.available()) {
			instance = new RtDevice(RayQuery.device());
		}
		return instance;
	}

	/** A buffer the GPU reads and writes, found by its device address. */
	RtBuffer deviceBuffer(long size, int usage) {
		return buffer(size, usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
	}

	/** A buffer the CPU writes (mapped for the buffer's life) and the GPU reads, also by device address. */
	RtBuffer hostBuffer(long size, int usage) {
		return buffer(size, usage | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, true);
	}

	/** Scratch memory for acceleration structure builds, aligned as the device needs. */
	RtBuffer scratchBuffer(long size) {
		// Over-allocated by the alignment, and its address rounded up to it.
		RtBuffer buffer = buffer(size + scratchAlignment, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
				| VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
		long aligned = (buffer.address() + scratchAlignment - 1) / scratchAlignment * scratchAlignment;
		return new RtBuffer(buffer.buffer(), buffer.allocation(), aligned, size, 0);
	}

	private RtBuffer buffer(long size, int usage, boolean host) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(Math.max(size, 16))
					.usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo allocation = VmaAllocationCreateInfo.calloc(stack)
					.usage(host ? VMA_MEMORY_USAGE_AUTO : VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE)
					.flags(host ? VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_MAPPED_BIT
							: 0);
			LongBuffer buffer = stack.mallocLong(1);
			PointerBuffer handle = stack.mallocPointer(1);
			VmaAllocationInfo allocated = VmaAllocationInfo.calloc(stack);
			check(vmaCreateBuffer(allocator, info, allocation, buffer, handle, allocated),
					"vmaCreateBuffer (" + size + " bytes)");
			long address = vkGetBufferDeviceAddress(device,
					VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer.get(0)));
			return new RtBuffer(buffer.get(0), handle.get(0), address, size, host ? allocated.pMappedData() : 0);
		}
	}

	/** Frees the buffer once the GPU can no longer be using it. */
	void free(RtBuffer buffer) {
		if (buffer != null) {
			later(() -> vmaDestroyBuffer(allocator, buffer.buffer(), buffer.allocation()));
		}
	}

	/** Destroys the acceleration structure once the GPU can no longer be using it. */
	void freeStructure(long structure) {
		if (structure != 0) {
			later(() -> vkDestroyAccelerationStructureKHR(device, structure, null));
		}
	}

	/** Runs {@code free} once every submission so far, and the next one, has finished. */
	void later(Runnable free) {
		deferred.add(new Deferred(nextSerial, frameSubmission(), free));
	}

	/** The index of the renderpearl submission being recorded now. */
	long frameSubmission() {
		return ((VulkanCommandEncoderAccessor) (Object) encoder).bedrockvisuals$currentSubmitIndex();
	}

	/** Every renderpearl submission up to this index has finished. */
	long completedFrameSubmission() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer value = stack.mallocLong(1);
			check(vkGetSemaphoreCounterValue(device, frameSemaphore, value), "vkGetSemaphoreCounterValue");
			return value.get(0);
		}
	}

	/** A primary command buffer of the ray tracing's own, for {@link #executeInFrame}. */
	VkCommandBuffer allocateCommandBuffer() {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer pointer = stack.mallocPointer(1);
			check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
					.commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer),
					"vkAllocateCommandBuffers");
			return new VkCommandBuffer(pointer.get(0), device);
		}
	}

	/**
	 * Records commands into a command buffer of the ray tracing's and hands it to renderpearl, to run in order
	 * within the frame renderpearl is recording (between its passes: never inside one). The command buffer must not
	 * be reused until {@link #completedFrameSubmission} reaches the {@link #frameSubmission} of now.
	 */
	void executeInFrame(VkCommandBuffer commands, Consumer<VkCommandBuffer> record) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkResetCommandBuffer(commands, 0);
			check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
					.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
			record.accept(commands);
			check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
		}
		encoder.execute(commands);
	}

	/** An acceleration structure over the whole of a storage buffer. */
	long createStructure(RtBuffer storage, int type) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer handle = stack.mallocLong(1);
			check(vkCreateAccelerationStructureKHR(device, VkAccelerationStructureCreateInfoKHR.calloc(stack)
					.sType$Default().buffer(storage.buffer()).size(storage.size()).type(type), null, handle),
					"vkCreateAccelerationStructureKHR");
			return handle.get(0);
		}
	}

	long structureAddress(long structure) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			return vkGetAccelerationStructureDeviceAddressKHR(device,
					VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default().accelerationStructure(structure));
		}
	}

	/**
	 * Records commands and submits them to the graphics queue (on the render thread, as renderpearl does), without
	 * waiting. Returns the submission's serial.
	 */
	long submit(Consumer<VkCommandBuffer> record) {
		poll();
		while (inFlight.size() >= MAX_IN_FLIGHT) {
			waitFor(inFlight.peekFirst().serial());
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkCommandBuffer commands = freeCommandBuffers.poll();
			if (commands == null) {
				PointerBuffer pointer = stack.mallocPointer(1);
				check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
						.commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pointer),
						"vkAllocateCommandBuffers");
				commands = new VkCommandBuffer(pointer.get(0), device);
			} else {
				vkResetCommandBuffer(commands, 0);
			}
			Long fence = freeFences.poll();
			if (fence == null) {
				LongBuffer handle = stack.mallocLong(1);
				check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, handle), "vkCreateFence");
				fence = handle.get(0);
			}
			check(vkBeginCommandBuffer(commands, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
					.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");
			record.accept(commands);
			check(vkEndCommandBuffer(commands), "vkEndCommandBuffer");
			check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(commands)),
					fence), "vkQueueSubmit");
			long serial = nextSerial++;
			inFlight.add(new InFlight(serial, commands, fence));
			return serial;
		}
	}

	/** Retires the finished submissions and frees what waited for them. */
	void poll() {
		while (!inFlight.isEmpty() && vkGetFenceStatus(device, inFlight.peekFirst().fence()) == VK_SUCCESS) {
			retire(inFlight.poll());
		}
		freeFinished();
	}

	/** Waits until the submission with this serial (and every earlier one) has finished. */
	void waitFor(long serial) {
		while (!inFlight.isEmpty() && inFlight.peekFirst().serial() <= serial) {
			InFlight first = inFlight.poll();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				check(vkWaitForFences(device, stack.longs(first.fence()), true, 10_000_000_000L), "vkWaitForFences");
			}
			retire(first);
		}
		freeFinished();
	}

	private void retire(InFlight done) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			vkResetFences(device, stack.longs(done.fence()));
		}
		freeFences.add(done.fence());
		freeCommandBuffers.add(done.commands());
		completedSerial = done.serial();
	}

	private void freeFinished() {
		if (deferred.isEmpty()) {
			return;
		}
		// A deferred free waits for the submission that came next after it (or, with nothing of ours in flight,
		// for none), and for the renderpearl submission recorded when it was made. Both only grow along the queue.
		long done = inFlight.isEmpty() ? Long.MAX_VALUE : completedSerial;
		long frameDone = completedFrameSubmission();
		while (!deferred.isEmpty() && deferred.peekFirst().serial() <= done
				&& deferred.peekFirst().frameSubmission() <= frameDone) {
			deferred.poll().free().run();
		}
	}

	static void check(int result, String call) {
		if (result != VK_SUCCESS) {
			throw new IllegalStateException(call + " returned " + result);
		}
	}
}
