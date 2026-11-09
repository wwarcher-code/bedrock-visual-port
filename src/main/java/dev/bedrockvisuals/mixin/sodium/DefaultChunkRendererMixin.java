package dev.bedrockvisuals.mixin.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.bedrockvisuals.compat.SodiumAdapter;
import dev.bedrockvisuals.render.GBufferPass;
import dev.bedrockvisuals.render.GBufferTerrainPipelines;
import dev.bedrockvisuals.render.ProbeTerrainPipelines;
import dev.bedrockvisuals.render.SceneProbe;
import dev.bedrockvisuals.render.ShadowPass;
import dev.bedrockvisuals.render.ShadowTerrainPipelines;
import dev.bedrockvisuals.render.WaterPass;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
abstract class DefaultChunkRendererMixin {
	/** Set while Sodium draws translucent terrain with the water inputs bound. */
	@Unique
	private static boolean bedrockvisuals$water;

	@Inject(method = "render", at = @At("HEAD"))
	private void bedrockvisuals$onRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists,
			TerrainRenderPass pass, CameraTransform camera, FogParameters fog, boolean indexedPass,
			RenderPass renderPass, GpuSampler sampler, GpuBufferSlice uniforms, GpuBuffer buffer, OitStage oitStage,
			CallbackInfo ci) {
		SodiumAdapter.onTerrainPass(pass, renderPass);
		if (GBufferPass.isActive()) {
			GBufferPass.recordMatrices(matrices.projection(), matrices.modelView());
		}
		if (ShadowPass.isActive()) {
			ShadowPass.countTerrainDraw();
		}
		bedrockvisuals$water = false;
		if (pass.isTranslucent() && !GBufferPass.isActive() && !ShadowPass.isActive() && !SceneProbe.isActive()
				&& WaterPass.isActive()) {
			try {
				WaterPass.bind(renderPass);
				bedrockvisuals$water = true;
			} catch (RuntimeException e) {
				WaterPass.fail(e);
			}
		}
	}

	/**
	 * During the G-buffer and shadow passes, draw with that pass's variant of whichever pipeline Sodium picked;
	 * in the translucent pass, with the water variant of each OIT stage's pipeline.
	 */
	@Redirect(method = "render", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderSystem;getCompiledPipeline(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)Lcom/mojang/renderpearl/api/pipeline/CompiledRenderPipeline;"))
	private CompiledRenderPipeline bedrockvisuals$usePassPipeline(RenderPipeline pipeline) {
		if (ShadowPass.isActive()) {
			pipeline = ShadowTerrainPipelines.variantOf(pipeline);
		} else if (SceneProbe.isActive()) {
			pipeline = ProbeTerrainPipelines.variantOf(pipeline);
		} else if (GBufferPass.isActive()) {
			pipeline = GBufferTerrainPipelines.variantOf(pipeline);
		} else if (bedrockvisuals$water && WaterPass.isActive()) {
			try {
				CompiledRenderPipeline water = RenderSystem.getCompiledPipeline(WaterPass.variantOf(pipeline));
				WaterPass.reportFirstUse();
				return water;
			} catch (RuntimeException e) {
				WaterPass.fail(e);
			}
		}
		return RenderSystem.getCompiledPipeline(pipeline);
	}

	/**
	 * Sodium skips the faces of each section that point away from the camera. The sun sees a different set,
	 * so the shadow pass draws them all.
	 */
	@Redirect(method = "fillCommandBuffer", at = @At(value = "INVOKE",
			target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;getVisibleFaces(IIIIII)I"))
	private static int bedrockvisuals$allFacesForShadows(int cameraX, int cameraY, int cameraZ, int sectionX,
			int sectionY, int sectionZ) {
		return ShadowPass.isActive() ? ModelQuadFacing.ALL
				: DefaultChunkRenderer.getVisibleFaces(cameraX, cameraY, cameraZ, sectionX, sectionY, sectionZ);
	}
}
