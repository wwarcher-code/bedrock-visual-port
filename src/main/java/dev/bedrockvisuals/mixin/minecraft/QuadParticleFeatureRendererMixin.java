package dev.bedrockvisuals.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.bedrockvisuals.render.EntityPass;
import dev.bedrockvisuals.render.EntityShadows;
import dev.bedrockvisuals.render.ParticlePass;
import org.spongepowered.asm.mixin.injection.ModifyArg;
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

	/** Whether the pipeline just swapped in is the Bedrock-lit variant (its inputs are bound after it is set). */
	private static boolean bedrockvisuals$lit;

	/** Particles are lit as entities are (ParticlePass): the variant in place of Minecraft's pipeline. */
	@ModifyArg(method = "drawLayers", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;"
			+ "getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)"
			+ "Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
	private static RenderPipeline bedrockvisuals$litPipeline(RenderPipeline pipeline) {
		bedrockvisuals$lit = ParticlePass.appliesTo(pipeline);
		return bedrockvisuals$lit ? ParticlePass.variantOf(pipeline) : pipeline;
	}

	@WrapOperation(method = "drawLayers", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/commands/"
			+ "RenderPass;setPipeline(Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;)V"))
	private static void bedrockvisuals$bindInputs(RenderPass pass, CompiledRenderPipeline pipeline,
			Operation<Void> original) {
		original.call(pass, pipeline);
		if (bedrockvisuals$lit) {
			EntityPass.bind(pass);
		}
	}
}
