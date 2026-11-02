package dev.bedrockvisuals.render;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.util.List;
import net.minecraft.resources.Identifier;

/**
 * Builds variants of Sodium's terrain pipelines: everything copied from Sodium's pipeline (bind groups, push
 * constants, vertex format, depth state, defines) so a variant accepts exactly what Sodium binds, with the
 * caller choosing the shaders, color targets and any extra bind groups.
 */
final class SodiumPipelineVariants {
	private SodiumPipelineVariants() {
	}

	/** @param name variant name, used for the shaders and the pipeline location, e.g. "gbuffer" */
	static RenderPipeline.Builder copy(RenderPipeline sodium, String name) {
		return copy(sodium, Identifier.fromNamespaceAndPath("bedrockvisuals", name + "/" + sodium.getLocation().getPath()),
				Identifier.fromNamespaceAndPath("bedrockvisuals", name + "/terrain"));
	}

	/** Any pipeline (not only Sodium's), with the given location and shader. */
	static RenderPipeline.Builder copy(RenderPipeline sodium, Identifier location, Identifier shader) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(location)
				.withVertexShader(shader)
				.withFragmentShader(shader)
				.withPushConstantSize(sodium.pushConstantSize())
				.withCull(sodium.isCull())
				.withPolygonMode(sodium.getPolygonMode())
				.withDepthStencilState(sodium.getDepthStencilState())
				.withPrimitiveTopology(sodium.getPrimitiveTopology());
		for (BindGroupLayout layout : sodium.getBindGroupLayouts()) {
			builder.withBindGroupLayout(layout);
		}
		List<VertexFormat> formats = sodium.getVertexFormatBindings();
		for (int i = 0; i < formats.size(); i++) {
			builder.withVertexBinding(i, formats.get(i));
		}
		sodium.getShaderDefines().flags().forEach(builder::withShaderDefine);
		sodium.getShaderDefines().values().forEach((define, value) -> addDefine(builder, define, value));
		return builder;
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
