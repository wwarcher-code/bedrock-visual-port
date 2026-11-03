package dev.bedrockvisuals.mixin.minecraft;

import dev.bedrockvisuals.render.WorldLightmap;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hands each frame's lightmap state to WorldLightmap. */
@Mixin(Lightmap.class)
abstract class LightmapMixin {
	@Inject(method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V", at = @At("HEAD"))
	private void bedrockvisuals$capture(LightmapRenderState state, CallbackInfo ci) {
		if (state.skyLightColor != null && state.ambientColor != null) {
			WorldLightmap.capture(state);
		}
	}
}
