package dev.bedrockvisuals.mixin.renderpearl;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** GlStateManager's active texture unit (GlCommandEncoderMixin). */
@Mixin(GlStateManager.class)
public interface GlStateManagerAccessor {
	@Accessor("activeTexture")
	static int bedrockvisuals$activeTexture() {
		throw new AssertionError();
	}
}
