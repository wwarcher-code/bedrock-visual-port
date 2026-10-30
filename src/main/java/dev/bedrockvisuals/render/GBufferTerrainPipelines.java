package dev.bedrockvisuals.render;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * G-buffer variants of Sodium's terrain pipelines. Each copies everything from Sodium's pipeline (bind
 * groups, push constants, vertex format, depth state, defines) so it accepts exactly what Sodium binds,
 * and swaps only the shaders and the color targets: one per {@link GBuffer.Target}.
 */
public final class GBufferTerrainPipelines {
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("bedrockvisuals", "gbuffer/terrain");
	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();

	private GBufferTerrainPipelines() {
	}

	public static RenderPipeline variantOf(RenderPipeline sodium) {
		return VARIANTS.computeIfAbsent(sodium, GBufferTerrainPipelines::build);
	}

	private static RenderPipeline build(RenderPipeline sodium) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "gbuffer/" + sodium.getLocation().getPath()))
				.withVertexShader(SHADER)
				.withFragmentShader(SHADER)
				.withPushConstantSize(sodium.pushConstantSize())
				.withCull(sodium.isCull())
				.withPolygonMode(sodium.getPolygonMode())
				.withDepthStencilState(sodium.getDepthStencilState())
				.withPrimitiveTopology(sodium.getPrimitiveTopology());
		for (BindGroupLayout layout : sodium.getBindGroupLayouts()) {
			builder.withBindGroupLayout(layout);
		}
		List<com.mojang.renderpearl.api.vertex.VertexFormat> formats = sodium.getVertexFormatBindings();
		for (int i = 0; i < formats.size(); i++) {
			builder.withVertexBinding(i, formats.get(i));
		}
		sodium.getShaderDefines().flags().forEach(builder::withShaderDefine);
		sodium.getShaderDefines().values().forEach((name, value) -> addDefine(builder, name, value));
		for (int i = 0; i < GBuffer.Target.values().length; i++) {
			builder.withColorTargetState(i, ColorTargetState.DEFAULT);
		}
		return builder.build();
	}

	/** The builder takes ints and floats; Sodium's defines are stored as their source text. */
	private static void addDefine(RenderPipeline.Builder builder, String name, String value) {
		try {
			builder.withShaderDefine(name, Integer.parseInt(value));
		} catch (NumberFormatException notInt) {
			builder.withShaderDefine(name, Float.parseFloat(value));
		}
	}
}
