package dev.bedrockvisuals.mixin.minecraft;

import com.mojang.blaze3d.vertex.MeshData;
import dev.bedrockvisuals.raytrace.EntityGeometry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Copies the vertices of the draws the ray-traced scene tagged as they arrive (raytrace/EntityGeometry). */
@Mixin(targets = "net.minecraft.client.renderer.StagedVertexBuffer$Draw")
abstract class StagedDrawMixin {
	@Inject(method = "append", at = @At("HEAD"))
	private void bedrockvisuals$captureVertices(MeshData mesh, CallbackInfo ci) {
		EntityGeometry.onVertices(this, mesh);
	}
}
