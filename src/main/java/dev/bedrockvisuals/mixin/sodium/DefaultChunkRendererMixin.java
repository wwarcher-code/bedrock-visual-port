package dev.bedrockvisuals.mixin.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.bedrockvisuals.compat.SodiumAdapter;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class DefaultChunkRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void bedrockvisuals$onRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
			TerrainRenderPass pass, CameraTransform camera, FogParameters fog, boolean indexedPass,
			RenderPass renderPass, GpuSampler sampler, GpuBufferSlice uniforms, GpuBuffer buffer, OitStage oitStage,
			CallbackInfo ci) {
		SodiumAdapter.onTerrainPass(pass, renderPass);
	}
}
