package dev.bedrockvisuals.mixin.voxy;

import dev.bedrockvisuals.compat.VoxyAdapter;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractRenderPipeline.class, remap = false)
abstract class AbstractRenderPipelineMixin {
	@Inject(method = "runPipeline", at = @At("HEAD"))
	private void bedrockvisuals$onRunPipeline(CallbackInfo ci) {
		VoxyAdapter.onPipelineRun((AbstractRenderPipeline) (Object) this);
	}
}
