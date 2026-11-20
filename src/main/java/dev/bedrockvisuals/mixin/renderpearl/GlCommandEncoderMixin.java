package dev.bedrockvisuals.mixin.renderpearl;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On the OpenGL backend, a texel buffer bound at texture unit 12 or above (the water shader's
 * u_SectionTimeInfo comes after its 12 samplers) leaves GlStateManager's active unit past the end of its
 * 12-unit table: the next texture created (an arm's skin, first drawn in first person) then crashes binding
 * it ("Index 12 out of bounds for length 12"). After each draw's bindings the active unit goes back to 0.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder") // not public
public abstract class GlCommandEncoderMixin {
	private static final int GL_TEXTURE0 = 0x84C0;
	/** GlStateManager's TEXTURES table's length. */
	private static final int TRACKED_UNITS = 12;

	@Inject(method = "setupDraw", at = @At("TAIL"))
	private void bedrockvisuals$activeUnitInRange(CallbackInfo info) {
		if (GlStateManagerAccessor.bedrockvisuals$activeTexture() >= TRACKED_UNITS) {
			GlStateManager._activeTexture(GL_TEXTURE0);
		}
	}
}
