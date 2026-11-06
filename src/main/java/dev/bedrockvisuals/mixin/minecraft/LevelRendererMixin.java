package dev.bedrockvisuals.mixin.minecraft;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.render.DeferredRenderer;
import dev.bedrockvisuals.render.EntityPass;
import dev.bedrockvisuals.render.GBufferDebugView;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureManager;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the frame graph's main pass (the lambda {@code addMainPass} registers): the deferred passes run just
 * before Minecraft opens its main render pass, the main pass skips the terrain they already drew, and the
 * debug view runs once the main pass is done. While Bedrock's sky replaces Java's, the frame clears to black.
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
		EntityPass.setInLevelPass(true);
		DeferredRenderer.beforeMainPass(chunks, main, chunkLayerSampler,
				(TextureAtlas) textureManager.getTexture(TextureAtlas.LOCATION_BLOCKS));
	}

	@Redirect(method = "executeSolid", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/textures/GpuSampler;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Z)V"))
	private void bedrockvisuals$skipLitTerrain(ChunkSectionsToRender chunks, ChunkSectionLayerGroup group,
			RenderPass pass, GpuSampler sampler, GpuTextureView atlas, boolean wireframe) {
		if (!(group == ChunkSectionLayerGroup.OPAQUE && DeferredRenderer.replacesTerrain())) {
			chunks.renderGroup(group, pass, sampler, atlas, wireframe);
		}
	}

	/** With Bedrock's sky drawn by the lighting pass, the frame clears to black, not Java's fog colour. */
	@ModifyArg(method = "lambda$render$0", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/commands/CommandEncoder;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"),
			index = 1)
	private Vector4fc bedrockvisuals$clearToBlack(Vector4fc color) {
		return DeferredRenderer.replacesSky() ? new Vector4f(0, 0, 0, 0) : color;
	}

	@Inject(method = "lambda$addMainPass$0", at = @At("TAIL"))
	private void bedrockvisuals$showDebugView(GpuBufferSlice fog, boolean arg1, ChunkSectionsToRender chunks,
			FeatureRenderDispatcher.PreparedFrame frame, boolean arg4, boolean arg5, CallbackInfo ci) {
		EntityPass.setInLevelPass(false);
		GBufferDebugView.apply(targets.main.get());
	}
}
