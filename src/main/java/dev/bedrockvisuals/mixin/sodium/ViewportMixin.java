package dev.bedrockvisuals.mixin.sodium;

import dev.bedrockvisuals.render.ShadowPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The shadow pass draws Sodium's visible sections from the sun, so a section out of the camera's view (behind
 * the player, above the screen) cast no shadow even where its shadow falls in view. Sodium's section test also
 * passes a section whose shadow can reach the view: the section moved away from the light, in steps out to
 * {@link #REACH} blocks, inside the frustum. Those sections are drawn in the main pass as well, off screen.
 */
@Mixin(value = Viewport.class, remap = false)
abstract class ViewportMixin {
	/** How far a shadow is followed from its caster (trees and cliffs; the cascades reach 128 blocks). */
	@Unique
	private static final float REACH = 64f;
	@Unique
	private static final int STEPS = 4;

	@Shadow
	@Final
	private Frustum frustum;
	@Shadow
	@Final
	private CameraTransform transform;

	@Inject(method = "isBoxVisible(III)Z", at = @At("RETURN"), cancellable = true)
	private void bedrockvisuals$shadowCasters(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) {
			cir.setReturnValue(bedrockvisuals$castsIntoView(x, y, z));
		}
	}

	@Inject(method = "isBoxVisibleLooser(III)Z", at = @At("RETURN"), cancellable = true)
	private void bedrockvisuals$shadowCastersLooser(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ()) {
			cir.setReturnValue(bedrockvisuals$castsIntoView(x, y, z));
		}
	}

	@Unique
	private boolean bedrockvisuals$castsIntoView(int x, int y, int z) {
		Vector3f towardsLight = ShadowPass.casterDirection();
		if (towardsLight == null) {
			return false;
		}
		float rx = (x - transform.intX) - transform.fracX;
		float ry = (y - transform.intY) - transform.fracY;
		float rz = (z - transform.intZ) - transform.fracZ;
		if (rx * rx + rz * rz > ShadowPass.RANGE * ShadowPass.RANGE * 1.5f) {
			return false;
		}
		float step = REACH / STEPS;
		for (int i = 1; i <= STEPS; i++) {
			float t = step * i;
			if (frustum.testSection(rx - towardsLight.x * t, ry - towardsLight.y * t, rz - towardsLight.z * t)) {
				return true;
			}
		}
		return false;
	}
}
