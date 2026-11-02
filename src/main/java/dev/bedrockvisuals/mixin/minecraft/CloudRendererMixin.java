package dev.bedrockvisuals.mixin.minecraft;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.bedrockvisuals.render.CloudPass;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every cloud draw (each transparency stage, fancy or flat) goes through {@code render(RenderPass, RenderPipeline)}:
 * while Bedrock's sky is on, it binds the lighting inputs and draws with the Bedrock-lit variant of the
 * pipeline (CloudPass).
 */
@Mixin(CloudRenderer.class)
abstract class CloudRendererMixin {
	@Inject(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
			at = @At("HEAD"))
	private void bedrockvisuals$bindLighting(RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
		if (CloudPass.isActive()) {
			try {
				CloudPass.bind(pass);
			} catch (RuntimeException e) {
				CloudPass.fail(e);
			}
		}
	}

	@ModifyVariable(method = "render(Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V",
			at = @At("HEAD"), argsOnly = true)
	private RenderPipeline bedrockvisuals$bedrockLitPipeline(RenderPipeline pipeline) {
		if (!CloudPass.isActive()) {
			return pipeline;
		}
		try {
			RenderPipeline variant = CloudPass.variantOf(pipeline);
			CloudPass.reportFirstUse();
			return variant;
		} catch (RuntimeException e) {
			CloudPass.fail(e);
			return pipeline;
		}
	}
}
