package dev.bedrockvisuals.mixin.minecraft;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.bedrockvisuals.render.TemporalAA;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Temporal anti-aliasing around the level (TemporalAA): the camera's projection is jittered before renderLevel
 * builds the level's projection from it (Sodium takes its own from the same call), and the frame is resolved
 * once the level is drawn, just before the hand (render3dHud), so the hand and the HUD are left out.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void bedrockvisuals$jitter(CallbackInfo ci) {
		TemporalAA.beginFrame(gameRenderState.levelRenderState.cameraRenderState);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void bedrockvisuals$resolve(CallbackInfo ci) {
		TemporalAA.resolve(mainRenderTarget);
	}
}
