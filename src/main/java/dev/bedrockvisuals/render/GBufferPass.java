package dev.bedrockvisuals.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * Draws Sodium's solid and cutout terrain into the G-buffer, in its own render pass just before Minecraft's
 * main pass. While it runs, Sodium's pipelines are swapped for their {@link GBufferTerrainPipelines G-buffer
 * variants}.
 *
 * <p>With deferred lighting on, the {@link LightingPass} then lights the G-buffer into the main target and
 * Minecraft's main pass skips Sodium's opaque terrain; with it off, the normal frame draws terrain as usual.
 */
public final class GBufferPass {
	private static final Vector4f CLEAR = new Vector4f(0, 0, 0, 0);
	/** Reversed depth: 0 is the far plane. */
	private static final double CLEAR_DEPTH = 0.0;

	private static final GBuffer GBUFFER = new GBuffer();
	private static boolean active;
	private static boolean reported;
	/** Set when the pass fails; the game keeps running without it and the error is logged once. */
	private static boolean disabled;
	private static final Matrix4f PROJECTION = new Matrix4f();
	private static final Matrix4f MODEL_VIEW = new Matrix4f();
	private static boolean drewTerrain;

	private GBufferPass() {
	}

	/** True while terrain is being drawn into the G-buffer. */
	public static boolean isActive() {
		return active;
	}

	public static GBuffer gbuffer() {
		return GBUFFER;
	}

	/** Called by the Sodium hook for each terrain draw in this pass. */
	public static void recordMatrices(Matrix4fc projection, Matrix4fc modelView) {
		PROJECTION.set(projection);
		MODEL_VIEW.set(modelView);
		drewTerrain = true;
	}

	/** True when this frame's G-buffer holds terrain, so it can replace the vanilla terrain draw. */
	public static boolean hasTerrain() {
		return drewTerrain && !disabled;
	}

	public static Matrix4fc projection() {
		return PROJECTION;
	}

	public static Matrix4fc modelView() {
		return MODEL_VIEW;
	}

	public static void render(ChunkSectionsToRender chunks, RenderTarget main, GpuSampler sampler, GpuTextureView blockAtlas) {
		drewTerrain = false;
		if (disabled) {
			return;
		}
		try {
			renderTerrain(chunks, main, sampler, blockAtlas);
		} catch (RuntimeException e) {
			disabled = true;
			drewTerrain = false;
			BedrockVisualsClient.LOGGER.error("[gbuffer] terrain pass failed; G-buffer disabled until restart", e);
			return;
		}
		if (!reported) {
			reported = true;
			BedrockVisualsClient.LOGGER.info("[gbuffer] first terrain pass drawn into {}x{} G-buffer", GBUFFER.width(),
					GBUFFER.height());
		}
	}

	private static void renderTerrain(ChunkSectionsToRender chunks, RenderTarget main, GpuSampler sampler,
			GpuTextureView blockAtlas) {
		GBUFFER.ensureSize(main.width, main.height);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals G-buffer terrain");
		for (GBuffer.Target target : GBuffer.Target.values()) {
			descriptor.withColorAttachment(GBUFFER.view(target), Optional.of(CLEAR));
		}
		descriptor.withDepthAttachment(GBUFFER.depthView(), OptionalDouble.of(CLEAR_DEPTH));

		active = true;
		try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
			RenderSystem.bindDefaultUniforms(pass);
			chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, blockAtlas, false);
		} finally {
			active = false;
		}
	}
}
