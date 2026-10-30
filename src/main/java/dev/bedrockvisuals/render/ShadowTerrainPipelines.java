package dev.bedrockvisuals.render;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shadow-map variants of Sodium's terrain pipelines: depth only, both faces drawn, positioned by the
 * {@link ShadowProjection} in the ShadowData uniform rather than Sodium's camera matrices (Sodium uploads
 * those once per frame, so a second view through its matrices would be ignored).
 */
public final class ShadowTerrainPipelines {
	static final BindGroupLayout SHADOW_DATA = BindGroupLayout.builder()
			.withUniform("ShadowData", UniformType.UNIFORM_BUFFER)
			.build();

	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();

	private ShadowTerrainPipelines() {
	}

	public static RenderPipeline variantOf(RenderPipeline sodium) {
		return VARIANTS.computeIfAbsent(sodium, ShadowTerrainPipelines::build);
	}

	private static RenderPipeline build(RenderPipeline sodium) {
		return SodiumPipelineVariants.copy(sodium, "shadow")
				.withBindGroupLayout(SHADOW_DATA)
				.withCull(false)
				.build();
	}
}
