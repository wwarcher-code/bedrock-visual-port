package dev.bedrockvisuals.raytrace;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.bedrockvisuals.mixin.renderpearl.FrontendGpuDeviceAccessor;
import dev.bedrockvisuals.mixin.renderpearl.VulkanDeviceAccessor;
import java.util.Set;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;

/**
 * Hardware ray queries (VK_KHR_ray_query over VK_KHR_acceleration_structure) on Minecraft's own Vulkan device,
 * for the offline preset's ray-traced lighting (docs/ray-tracing-notes.md). They are an optional feature set
 * (VulkanFeatureSetsMixin): enabled at device creation where the GPU has them, and absent otherwise, in which
 * case everything ray-traced stays off and the renderer works as before.
 *
 * <p>The ray tracing runs on raw Vulkan (LWJGL) beside renderpearl: renderpearl has no acceleration structures,
 * and its allocator isn't created with buffer device addresses, so the ray tracing allocates its own memory.
 */
public final class RayQuery {
	private static FeatureSet featureSet;

	private RayQuery() {
	}

	/** The feature set renderpearl enables at device creation when the GPU supports it. */
	public static FeatureSet featureSet() {
		if (featureSet == null) {
			VulkanPNextStruct accelerationStructure = new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class);
			VulkanPNextStruct rayQuery = new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class);
			featureSet = new FeatureSet("Bedrock Visuals ray queries",
					Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_deferred_host_operations"),
					Set.of(new VulkanFeature(accelerationStructure, "accelerationStructure"),
							new VulkanFeature(rayQuery, "rayQuery"),
							// Acceleration structures are built from and found by device addresses (core in 1.2).
							new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress")));
		}
		return featureSet;
	}

	/** Minecraft's Vulkan device, or null on another backend. */
	public static VulkanDevice device() {
		if (RenderSystem.getDevice() instanceof FrontendGpuDeviceAccessor frontend
				&& frontend.bedrockvisuals$backend() instanceof VulkanDevice vulkan) {
			return vulkan;
		}
		return null;
	}

	/** Whether the device was created with the ray queries. */
	public static boolean available() {
		VulkanDevice device = device();
		if (device == null) {
			return false;
		}
		// The device's features don't change: asked once a device (it is asked for every section and entity).
		if (device != checkedDevice) {
			checkedDevice = device;
			checkedAvailable = featureSet != null
					&& ((VulkanDeviceAccessor) (Object) device).bedrockvisuals$enabledFeatures().contains(featureSet);
		}
		return checkedAvailable;
	}

	private static VulkanDevice checkedDevice;
	private static boolean checkedAvailable;

	/** A one-line summary for diagnostics. */
	public static String status() {
		VulkanDevice device = device();
		if (device == null) {
			return "off (not on Vulkan)";
		}
		if (!available()) {
			return "off (the GPU or driver has no ray queries)";
		}
		return "on; " + RayQuerySmokeTest.status();
	}
}
