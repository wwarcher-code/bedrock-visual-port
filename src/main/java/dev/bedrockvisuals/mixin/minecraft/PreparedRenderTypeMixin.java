package dev.bedrockvisuals.mixin.minecraft;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.bedrockvisuals.render.EntityPass;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every render-type draw (entities, block entities, items, in each transparency stage) goes through
 * {@code draw(ExecuteInfo, RenderPass, RenderPipeline)}: for Minecraft's entity shader during the level's main
 * pass, it binds the lighting inputs and draws with the Bedrock-lit variant (EntityPass).
 */
@Mixin(PreparedRenderType.class)
abstract class PreparedRenderTypeMixin {
	private static final String DRAW = "draw(Lnet/minecraft/client/renderer/StagedVertexBuffer$ExecuteInfo;"
			+ "Lcom/mojang/renderpearl/api/commands/RenderPass;Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V";

	@Inject(method = DRAW, at = @At("HEAD"))
	private void bedrockvisuals$bindLighting(StagedVertexBuffer.ExecuteInfo info, RenderPass pass,
			RenderPipeline pipeline, CallbackInfo ci) {
		if (EntityPass.appliesTo(pipeline) || EntityPass.isVariant(pipeline)) {
			try {
				EntityPass.bind(pass);
			} catch (RuntimeException e) {
				EntityPass.fail(e);
			}
		}
	}

	@ModifyVariable(method = DRAW, at = @At("HEAD"), argsOnly = true)
	private RenderPipeline bedrockvisuals$bedrockLitPipeline(RenderPipeline pipeline) {
		if (!EntityPass.appliesTo(pipeline)) {
			return pipeline;
		}
		try {
			RenderPipeline variant = EntityPass.variantOf(pipeline);
			EntityPass.reportFirstUse();
			return variant;
		} catch (RuntimeException e) {
			EntityPass.fail(e);
			return pipeline;
		}
	}
}
