package dev.bedrockvisuals.mixin.minecraft;

import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Gives the first-person shadow's own dispatcher a vertex buffer of its own (FirstPersonShadow). */
@Mixin(FeatureRenderDispatcher.class)
public interface FeatureRenderDispatcherAccessor {
	@Mutable
	@Accessor("stagedVertexBuffer")
	void bedrockvisuals$setStagedVertexBuffer(StagedVertexBuffer buffer);
}
