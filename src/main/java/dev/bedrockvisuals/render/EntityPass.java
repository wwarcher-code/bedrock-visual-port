package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * Bedrock-lit entities. Every entity, block entity and item Minecraft draws in the level's main pass goes through
 * one shader, core/entity, switched by defines (cutout, emissive, glint, the transparency stages...), and every
 * such draw through PreparedRenderType.draw. While the lighting pass lights the frame, each of those pipelines
 * gets a variant with the same state and the shader entity/entity, which lights the entity as terrain is lit
 * (the sun and moon through the shadow cascades, Bedrock's sky and block light), dims it by any water in front
 * of it, fogs it, and grades it with the rest of the frame. The first-person hand, drawn after the level with
 * its own projection, keeps Minecraft's lighting.
 */
public final class EntityPass {
	static final BindGroupLayout INPUTS = LocalExposure.withInputs(BindGroupLayout.builder()
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
			.withUniform("ShadowMap0", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowMap1", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowMap2", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowMap3", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("WaterColumnMap", UniformType.COMBINED_IMAGE_SAMPLER)
			).build();
	private static final Identifier ENTITY_SHADER = Identifier.withDefaultNamespace("core/entity");
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("bedrockvisuals", "entity/entity");

	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();
	/** True while Minecraft draws the level's main pass (LevelRendererMixin): the hand comes after it. */
	private static boolean inLevelPass;
	private static boolean failed;
	private static boolean reported;

	private EntityPass() {
	}

	public static void setInLevelPass(boolean inLevel) {
		inLevelPass = inLevel;
	}

	/** Whether this draw's pipeline is one this pass replaces right now. */
	public static boolean appliesTo(RenderPipeline pipeline) {
		return !failed && inLevelPass && DeferredRenderer.replacesSky() && LightingPass.lightingData() != null
				&& LightingPass.atmosphereData() != null && LightingPass.gradingData() != null
				&& ENTITY_SHADER.equals(pipeline.getShaders().get(ShaderType.FRAGMENT));
	}

	/** Whether the pipeline is one of this pass's variants (mixin injectors at the same point run in any order). */
	public static boolean isVariant(RenderPipeline pipeline) {
		return SHADER.equals(pipeline.getShaders().get(ShaderType.FRAGMENT));
	}

	/** Binds the lighting inputs on the pass the entity is about to be drawn into. */
	public static void bind(RenderPass pass) {
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		pass.setUniform("LightingData", LightingPass.lightingData());
		pass.setUniform("GradingData", LightingPass.gradingData());
		pass.setUniform("AtmosphereData", LightingPass.atmosphereData());
		// Without a shadow map this frame the shader skips the lookups (ShadowParams.x), but every declared
		// sampler must be bound.
		var fallback = CloudShadows.viewOrEmpty();
		for (int c = 0; c < ShadowPass.CASCADES; c++) {
			var map = ShadowPass.depthView(c);
			pass.setUniform("ShadowMap" + c, map != null ? map : fallback, nearest);
		}
		pass.setUniform("WaterColumnMap", WaterColumns.view() != null ? WaterColumns.view() : fallback, nearest);
		LocalExposure.bind(pass);
	}

	public static RenderPipeline variantOf(RenderPipeline vanilla) {
		return VARIANTS.computeIfAbsent(vanilla, EntityPass::build);
	}

	private static RenderPipeline build(RenderPipeline vanilla) {
		RenderPipeline.Builder builder = SodiumPipelineVariants.copy(vanilla,
				Identifier.fromNamespaceAndPath("bedrockvisuals", "entity/" + vanilla.getLocation().getPath()), SHADER)
				.withBindGroupLayout(INPUTS);
		List<ColorTargetState> targets = vanilla.getColorTargetStates();
		for (int i = 0; i < targets.size(); i++) {
			builder.withColorTargetState(i, targets.get(i));
		}
		return builder.build();
	}

	/** The variant failed to build or draw: Minecraft's entity lighting until restart. */
	public static void fail(RuntimeException e) {
		if (!failed) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[entities] Bedrock entity lighting failed; Minecraft's until restart", e);
		}
	}

	public static void reportFirstUse() {
		if (!reported) {
			reported = true;
			BedrockVisualsClient.LOGGER.info("[entities] entities drawn with Bedrock's lighting");
		}
	}

	/** A one-line summary for diagnostics. */
	public static String status() {
		return failed ? "FAILED (see log)" : reported ? "ok" : "not drawn yet";
	}
}
