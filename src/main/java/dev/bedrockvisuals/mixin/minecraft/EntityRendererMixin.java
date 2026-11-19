package dev.bedrockvisuals.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.bedrockvisuals.raytrace.RtScene;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While the ray-traced scene is on, entities near the camera are drawn even out of view: rays that leave the
 * screen (shadows, reflections, bounced light) still meet them.
 */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
	@WrapOperation(method = "shouldRender", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;isVisible(Lnet/minecraft/world/phys/AABB;)Z"))
	private boolean bedrockvisuals$keepForRays(Frustum frustum, AABB box, Operation<Boolean> original) {
		if (original.call(frustum, box)) {
			return true;
		}
		var center = box.getCenter();
		return RtScene.keepsOffScreen(center.x, center.y, center.z);
	}
}
