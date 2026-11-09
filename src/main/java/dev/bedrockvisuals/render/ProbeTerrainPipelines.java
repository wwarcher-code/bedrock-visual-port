package dev.bedrockvisuals.render;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The scene probe's variants of Sodium's terrain pipelines: the G-buffer's targets and materials, positioned by
 * one face of the probe's cube (the ProbeFace uniform) rather than Sodium's camera matrices.
 */
public final class ProbeTerrainPipelines {
	static final BindGroupLayout PROBE_FACE = BindGroupLayout.builder()
			.withUniform("ProbeFace", UniformType.UNIFORM_BUFFER)
			.build();

	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();

	private ProbeTerrainPipelines() {
	}

	public static RenderPipeline variantOf(RenderPipeline sodium) {
		return VARIANTS.computeIfAbsent(sodium, ProbeTerrainPipelines::build);
	}

	private static RenderPipeline build(RenderPipeline sodium) {
		RenderPipeline.Builder builder = SodiumPipelineVariants.copy(sodium, "probe")
				.withBindGroupLayout(GBufferTerrainPipelines.MATERIALS)
				.withBindGroupLayout(PROBE_FACE);
		for (int i = 0; i < GBuffer.Target.values().length; i++) {
			builder.withColorTargetState(i, ColorTargetState.DEFAULT);
		}
		return builder.build();
	}
}
