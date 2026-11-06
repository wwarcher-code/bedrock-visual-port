package dev.bedrockvisuals.mixin.minecraft;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.bedrockvisuals.render.DeferredRenderer;
import dev.bedrockvisuals.render.SunMoon;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.client.renderer.SkyRenderer;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While the lighting pass draws Bedrock's sky, Java's sky pass draws only its sun, moon and stars: the sky
 * gradient, the sunset fan and the dark disc below the horizon are Bedrock's atmosphere's job, and the sun and
 * moon are drawn by the lighting pass from Bedrock's textures (SunMoon) once those are loaded.
 */
@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
	@Inject(method = "renderSkyDisc", at = @At("HEAD"), cancellable = true)
	private void bedrockvisuals$skipSkyDisc(RenderPass pass, Vector3fc color, CallbackInfo ci) {
		if (DeferredRenderer.replacesSky()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderSunriseAndSunset", at = @At("HEAD"), cancellable = true)
	private void bedrockvisuals$skipSunset(RenderPass pass, PoseStack poseStack, float angle, Vector4fc color,
			CallbackInfo ci) {
		if (DeferredRenderer.replacesSky()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderDarkDisc", at = @At("HEAD"), cancellable = true)
	private void bedrockvisuals$skipDarkDisc(RenderPass pass, CallbackInfo ci) {
		if (DeferredRenderer.replacesSky()) {
			ci.cancel();
		}
	}

	@Inject(method = "render", at = @At("HEAD"))
	private void bedrockvisuals$recordSky(GpuBufferSlice fog, SkyRenderState state, CallbackInfo ci) {
		SunMoon.recordSkyState(state.moonPhase == null ? 0 : state.moonPhase.index(), state.rainBrightness);
	}

	@Inject(method = "renderSun", at = @At("HEAD"), cancellable = true)
	private void bedrockvisuals$skipSun(RenderPass pass, float alpha, PoseStack poseStack, CallbackInfo ci) {
		if (SunMoon.drawsThem()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderMoon", at = @At("HEAD"), cancellable = true)
	private void bedrockvisuals$skipMoon(RenderPass pass, MoonPhase phase, float alpha, PoseStack poseStack,
			CallbackInfo ci) {
		if (SunMoon.drawsThem()) {
			ci.cancel();
		}
	}
}
