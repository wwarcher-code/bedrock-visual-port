package dev.bedrockvisuals.mixin.sodium;

import dev.bedrockvisuals.compat.SodiumAdapter;
import dev.bedrockvisuals.raytrace.RtScene;
import dev.bedrockvisuals.raytrace.SectionGeometry;
import java.util.List;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands the ray-traced scene (raytrace/SectionGeometry) each section's new mesh as Sodium takes it in, before it
 * uploads and frees it, and tells it when sections unload.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
abstract class RenderSectionManagerMixin {
	@Inject(method = "updateWithResult", at = @At("HEAD"))
	private void bedrockvisuals$meshForRayTracing(Viewport viewport, RenderSection section, ChunkBuildOutput output,
			List<RenderSection> sections, CallbackInfoReturnable<Integer> cir) {
		SodiumAdapter.onSectionBuilt(section, output);
	}

	@Inject(method = "onSectionRemoved", at = @At("HEAD"))
	private void bedrockvisuals$sectionGone(int x, int y, int z, CallbackInfo ci) {
		SectionGeometry.onSectionRemoved(x, y, z);
	}

	@Inject(method = "destroy", at = @At("HEAD"))
	private void bedrockvisuals$rendererGone(CallbackInfo ci) {
		RtScene.onWorldClosed();
	}
}
