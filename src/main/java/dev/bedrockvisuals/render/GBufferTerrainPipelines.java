package dev.bedrockvisuals.render;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** G-buffer variants of Sodium's terrain pipelines: one color target per {@link GBuffer.Target}. */
public final class GBufferTerrainPipelines {
	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();

	private GBufferTerrainPipelines() {
	}

	public static RenderPipeline variantOf(RenderPipeline sodium) {
		return VARIANTS.computeIfAbsent(sodium, GBufferTerrainPipelines::build);
	}

	private static RenderPipeline build(RenderPipeline sodium) {
		RenderPipeline.Builder builder = SodiumPipelineVariants.copy(sodium, "gbuffer");
		for (int i = 0; i < GBuffer.Target.values().length; i++) {
			builder.withColorTargetState(i, ColorTargetState.DEFAULT);
		}
		return builder.build();
	}
}
