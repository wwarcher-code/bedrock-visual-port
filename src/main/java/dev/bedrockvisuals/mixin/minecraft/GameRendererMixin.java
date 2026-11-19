package dev.bedrockvisuals.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.bedrockvisuals.render.OfflineRender;
import dev.bedrockvisuals.render.RenderHooks;
import dev.bedrockvisuals.render.TemporalAA;
import net.minecraft.client.renderer.GameRenderer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Around the level's render:
 * <ul>
 * <li>Temporal anti-aliasing (TemporalAA): the camera's projection is jittered before renderLevel builds the
 * level's projection from it (Sodium takes its own from the same call), and the frame is resolved once the level
 * is drawn, just before the hand (render3dHud), so the hand and the HUD are left out.</li>
 * <li>The offline export's sub-frames (OfflineRender): the level, extracted once a frame, is drawn several
 * times, each jittered and added to an average just before the hand, which then draws over the average.</li>
 * </ul>
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Shadow
	@Final
	private GameRenderState gameRenderState;

	@Shadow
	@Final
	private RenderTarget mainRenderTarget;

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
	private void bedrockvisuals$subframes(GameRenderer renderer, Operation<Void> original) {
		int count = OfflineRender.subframesThisFrame();
		if (count <= 1) {
			original.call(renderer);
			return;
		}
		// LevelRenderer.submitFeatures empties the extracted entities, block entities and block-breaking stages
		// once it has submitted them: each later draw gets them back.
		LevelRenderState level = gameRenderState.levelRenderState;
		List<EntityRenderState> entities = new ArrayList<>(level.entityRenderStates);
		List<BlockEntityRenderState> blockEntities = new ArrayList<>(level.blockEntityRenderStates);
		List<BlockBreakingRenderState> breaking = new ArrayList<>(level.blockBreakingRenderStates);
		try {
			for (int i = 0; i < count; i++) {
				if (i > 0) {
					restore(level.entityRenderStates, entities);
					restore(level.blockEntityRenderStates, blockEntities);
					restore(level.blockBreakingRenderStates, breaking);
				}
				OfflineRender.beginSubframe(i, count);
				original.call(renderer);
			}
		} finally {
			OfflineRender.endFrame();
		}
	}

	private static <T> void restore(List<T> list, List<T> saved) {
		list.clear();
		list.addAll(saved);
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void bedrockvisuals$jitter(CallbackInfo ci) {
		RenderHooks.beginLevel();
		dev.bedrockvisuals.raytrace.EntityGeometry.beginLevel();
		TemporalAA.beginFrame(gameRenderState.levelRenderState.cameraRenderState);
	}

	@Inject(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void bedrockvisuals$resolve(CallbackInfo ci) {
		dev.bedrockvisuals.raytrace.RtScene.afterLevel(gameRenderState.levelRenderState.cameraRenderState);
		TemporalAA.resolve(mainRenderTarget);
		RenderHooks.afterLevel(mainRenderTarget);
	}
}
