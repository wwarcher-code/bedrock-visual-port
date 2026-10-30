package dev.bedrockvisuals.mixin.minecraft;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.bedrockvisuals.render.GBufferDebugView;
import dev.bedrockvisuals.render.GBufferPass;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the frame graph's main pass (the lambda {@code addMainPass} registers): the G-buffer terrain pass
 * runs just before Minecraft opens its main render pass, and the debug view runs once that pass is done.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	@Shadow
	@Final
	private LevelTargetBundle targets;

	@Shadow
	@Final
	private TextureManager textureManager;

	@Shadow
	private GpuSampler chunkLayerSampler;

	@Inject(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", ordinal = 0,
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/renderpearl/api/commands/RenderPass;"))
	private void bedrockvisuals$renderGBuffer(GpuBufferSlice fog, boolean arg1, ChunkSectionsToRender chunks,
			FeatureRenderDispatcher.PreparedFrame frame, boolean arg4, boolean arg5, CallbackInfo ci) {
		RenderTarget main = targets.main.get();
		GBufferPass.render(chunks, main, chunkLayerSampler,
				textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView());
	}

	@Inject(method = "lambda$addMainPass$0", at = @At("TAIL"))
	private void bedrockvisuals$showDebugView(GpuBufferSlice fog, boolean arg1, ChunkSectionsToRender chunks,
			FeatureRenderDispatcher.PreparedFrame frame, boolean arg4, boolean arg5, CallbackInfo ci) {
		GBufferDebugView.apply(targets.main.get());
	}
}
