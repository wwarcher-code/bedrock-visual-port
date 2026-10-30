package dev.bedrockvisuals.mixin.replaymod;

import com.replaymod.render.rendering.VideoRenderer;
import dev.bedrockvisuals.compat.ReplayModAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VideoRenderer.class, remap = false)
abstract class VideoRendererMixin {
	@Inject(method = "setup", at = @At("TAIL"))
	private void bedrockvisuals$onSetup(CallbackInfo ci) {
		VideoRenderer self = (VideoRenderer) (Object) this;
		ReplayModAdapter.onExportStart(self.getTotalFrames());
	}

	@Inject(method = "finish", at = @At("HEAD"))
	private void bedrockvisuals$onFinish(CallbackInfo ci) {
		VideoRenderer self = (VideoRenderer) (Object) this;
		ReplayModAdapter.onExportEnd(self.getFramesDone());
	}
}
