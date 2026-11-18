package dev.bedrockvisuals.mixin.renderpearl;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Which feature sets the device was created with (whether the ray queries made it in). */
@Mixin(VulkanDevice.class)
public interface VulkanDeviceAccessor {
	@Accessor("enabledFeatures")
	FeatureSet bedrockvisuals$enabledFeatures();
}
