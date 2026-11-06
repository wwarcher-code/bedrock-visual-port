package dev.bedrockvisuals.mixin.minecraft;

import dev.bedrockvisuals.render.EntityShadows;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Particles in the solid phase set their own pipeline on the pass rather than going through
 * PreparedRenderType.draw, so the entity shadow pass (which replays the solid phase into the shadow cascades)
 * skips them here: their pipeline's colour format does not match the cascade's, and particles cast no shadow.
 */
@Mixin(QuadParticleFeatureRenderer.class)
abstract class QuadParticleFeatureRendererMixin {
	@Inject(method = "executeGroup", at = @At("HEAD"), cancellable = true)
	private void bedrockvisuals$skipInShadowPass(CallbackInfo ci) {
		if (EntityShadows.casting()) {
			ci.cancel();
		}
	}
}
