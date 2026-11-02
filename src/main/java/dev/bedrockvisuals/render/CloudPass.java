package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.lod.DistantTerrain;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * Bedrock-lit clouds. Java draws its cloud mesh with a pipeline per order-independent transparency stage; each
 * gets a variant with the same state and the shader clouds/clouds, which lights the cells as Bedrock's
 * CloudsForwardPBR does, from the frame's lighting, atmosphere and grading (shared with the lighting pass).
 * On while the lighting pass draws Bedrock's sky; otherwise Java's clouds are drawn as they are.
 * Distant Horizons draws its own clouds farther out with its own shader, which this can't light, so they're
 * hidden for the session while these are on (raise Java's Cloud Distance to see clouds farther).
 */
public final class CloudPass {
	static final BindGroupLayout INPUTS = BindGroupLayout.builder()
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
			.withUniform("CloudCells", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("BrdfLut", UniformType.COMBINED_IMAGE_SAMPLER)
			.build();
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("bedrockvisuals", "clouds/clouds");

	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();
	private static boolean failed;
	private static boolean reported;

	private CloudPass() {
	}

	/** Whether this frame's clouds are drawn with Bedrock's lighting. */
	public static boolean isActive() {
		return !failed && DeferredRenderer.replacesSky() && LightingPass.lightingData() != null
				&& LightingPass.atmosphereData() != null;
	}

	private static boolean hidingDistantClouds;

	/**
	 * Once a frame: while the clouds are Bedrock-lit, hides Distant Horizons' own (unlit) clouds; restores
	 * them when they aren't.
	 */
	static void updateDistantClouds() {
		boolean hide = isActive();
		if (hide != hidingDistantClouds) {
			hidingDistantClouds = hide;
			DistantTerrain.source().ifPresent(source -> source.hideOwnClouds(hide));
		}
	}

	/** Binds the lighting inputs on the pass Java's clouds are about to be drawn into. */
	public static void bind(RenderPass pass) {
		pass.setUniform("LightingData", LightingPass.lightingData());
		pass.setUniform("GradingData", LightingPass.gradingData());
		pass.setUniform("AtmosphereData", LightingPass.atmosphereData());
		// Which cells are filled (CloudShadows), for paths through neighbouring cells.
		pass.setUniform("CloudCells", CloudShadows.viewOrEmpty(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
		// Bedrock's BRDF table for the reflected sky; without it (DebugParams.z 0) the shader approximates it.
		pass.setUniform("BrdfLut", BrdfLut.view() != null ? BrdfLut.view() : CloudShadows.viewOrEmpty(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
	}

	public static RenderPipeline variantOf(RenderPipeline vanilla) {
		return VARIANTS.computeIfAbsent(vanilla, CloudPass::build);
	}

	private static RenderPipeline build(RenderPipeline vanilla) {
		RenderPipeline.Builder builder = SodiumPipelineVariants.copy(vanilla,
				Identifier.fromNamespaceAndPath("bedrockvisuals", "clouds/" + vanilla.getLocation().getPath()), SHADER)
				.withBindGroupLayout(INPUTS);
		List<ColorTargetState> targets = vanilla.getColorTargetStates();
		for (int i = 0; i < targets.size(); i++) {
			builder.withColorTargetState(i, targets.get(i));
		}
		return builder.build();
	}

	/** The variant failed to build or draw: Java's clouds until restart. */
	public static void fail(RuntimeException e) {
		if (!failed) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[clouds] Bedrock cloud lighting failed; Java's clouds until restart", e);
		}
	}

	public static void reportFirstUse() {
		if (!reported) {
			reported = true;
			BedrockVisualsClient.LOGGER.info("[clouds] clouds drawn with Bedrock's cloud lighting");
		}
	}

	/** A one-line summary for diagnostics. */
	public static String status() {
		return failed ? "FAILED (see log)" : reported ? "ok"
				: "not drawn yet (Java's Clouds setting must be Fast or Fancy, with Distant Horizons' clouds off)";
	}
}
