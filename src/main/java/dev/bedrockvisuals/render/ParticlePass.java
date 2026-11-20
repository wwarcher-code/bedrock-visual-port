package dev.bedrockvisuals.render;

import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.Identifier;

/**
 * Bedrock-lit particles. Minecraft draws them with its lightmap into the graded image, so beside bright lava its
 * smoke came out nearly black, nothing else in the frame being lit that way. Like entities (EntityPass), each
 * particle pipeline drawn in the level's main pass gets a variant: Minecraft's vertex (particle/particle.vsh)
 * feeding the entities' fragment shader (entity/entity.fsh with PARTICLE): lit by the sun and moon, Bedrock's sky
 * and block light or the ray-traced light, through water and fog, exposed and graded with the frame. Particles
 * Minecraft draws full bright (flames, lava's embers) glow as they are.
 */
public final class ParticlePass {
	private static final Identifier PARTICLE_SHADER = Identifier.withDefaultNamespace("core/particle");
	private static final Identifier VERTEX = Identifier.fromNamespaceAndPath("bedrockvisuals", "particle/particle");
	private static final Identifier FRAGMENT = Identifier.fromNamespaceAndPath("bedrockvisuals", "entity/entity");
	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();
	private static boolean reported;

	private ParticlePass() {
	}

	/** Whether this particle pipeline is replaced right now (the lighting is ready, in the level's main pass). */
	public static boolean appliesTo(RenderPipeline pipeline) {
		return EntityPass.lightingReady() && PARTICLE_SHADER.equals(pipeline.getShaders().get(ShaderType.FRAGMENT));
	}

	public static RenderPipeline variantOf(RenderPipeline vanilla) {
		if (!reported) {
			reported = true;
			dev.bedrockvisuals.BedrockVisualsClient.LOGGER.info("[particles] particles drawn with Bedrock's lighting");
		}
		return VARIANTS.computeIfAbsent(vanilla, ParticlePass::build);
	}

	private static RenderPipeline build(RenderPipeline vanilla) {
		RenderPipeline.Builder builder = SodiumPipelineVariants.copy(vanilla,
				Identifier.fromNamespaceAndPath("bedrockvisuals", "particle/" + vanilla.getLocation().getPath()), FRAGMENT)
				.withVertexShader(VERTEX)
				.withShaderDefine("PARTICLE")
				.withShaderDefine("ALPHA_CUTOUT", 0.1f)
				.withBindGroupLayout(EntityPass.INPUTS);
		List<ColorTargetState> targets = vanilla.getColorTargetStates();
		for (int i = 0; i < targets.size(); i++) {
			builder.withColorTargetState(i, targets.get(i));
		}
		return builder.build();
	}
}
