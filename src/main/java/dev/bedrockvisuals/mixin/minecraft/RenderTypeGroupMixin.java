package dev.bedrockvisuals.mixin.minecraft;

import dev.bedrockvisuals.raytrace.EntityGeometry;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Tells the ray-traced scene which staged draw each render type's vertices go to (raytrace/EntityGeometry). */
@Mixin(targets = "net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer$Group")
abstract class RenderTypeGroupMixin {
	@Inject(method = "getOrAddDraw", at = @At("RETURN"))
	private void bedrockvisuals$tagDraw(RenderType type, CallbackInfoReturnable<Object> cir) {
		EntityGeometry.onDrawAdded(cir.getReturnValue(), type);
	}
}
