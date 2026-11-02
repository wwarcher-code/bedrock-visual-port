package dev.bedrockvisuals.mixin.minecraft;

import dev.bedrockvisuals.render.CloudShadows;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands the cloud field and where it has drifted to over to CloudShadows, once a frame. */
@Mixin(CloudRenderer.class)
abstract class CloudRendererPrepareMixin {
	@Shadow
	private CloudRenderer.TextureData texture;

	@Inject(method = "prepare(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V", at = @At("HEAD"))
	private void bedrockvisuals$trackClouds(int color, CloudStatus status, float cloudHeight, int range, Vec3 camera,
			long gameTime, float partialTick, CallbackInfo ci) {
		CloudRenderer.TextureData data = texture;
		CloudShadows.prepare(data == null ? null : data.cells(), data == null ? 0 : data.width(),
				data == null ? 0 : data.height(), status, cloudHeight, camera, gameTime, partialTick);
	}
}
