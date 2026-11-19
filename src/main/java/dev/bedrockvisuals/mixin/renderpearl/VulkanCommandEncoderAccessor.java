package dev.bedrockvisuals.mixin.renderpearl;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * renderpearl's frame submissions, for the ray tracing's resources used inside them (raytrace/RtDevice): each
 * submission signals the timeline semaphore with its index when it finishes.
 */
@Mixin(value = VulkanCommandEncoder.class, remap = false)
public interface VulkanCommandEncoderAccessor {
	@Accessor("submitSemaphore")
	long bedrockvisuals$submitSemaphore();

	/** The index of the submission being recorded now. */
	@Accessor("currentSubmitIndex")
	long bedrockvisuals$currentSubmitIndex();
}
