package dev.bedrockvisuals.mixin.renderpearl;

import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The backend behind Minecraft's device (the Vulkan device, for the ray queries' raw Vulkan calls). */
@Mixin(FrontendGpuDevice.class)
public interface FrontendGpuDeviceAccessor {
	@Accessor("backend")
	GpuDeviceBackend bedrockvisuals$backend();
}
