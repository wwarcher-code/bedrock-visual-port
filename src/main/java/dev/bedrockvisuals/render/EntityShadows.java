package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;

/**
 * Entities' shadows. Minecraft prepares the frame's entity draws before its main pass opens; the main pass draws
 * them with executeSolid. Before the lighting pass, that same call is made once more into each shadow cascade
 * (after the terrain's shadows, keeping their depth), with every entity pipeline swapped for a variant whose
 * vertex shader goes on from the camera's clip space to the cascade's (entity/shadow) and every other draw in
 * the phase skipped. The lighting pass then shadows terrain (and the entity shader shadows entities) with
 * entities in the maps.
 */
public final class EntityShadows {
	static final BindGroupLayout SHADOW_DATA = BindGroupLayout.builder()
			.withUniform("EntityShadowData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final int DATA_SIZE = 2 * 64;
	private static final Identifier ENTITY_SHADER = Identifier.withDefaultNamespace("core/entity");
	private static final Identifier SHADER = Identifier.fromNamespaceAndPath("bedrockvisuals", "entity/shadow");

	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();
	private static final GpuBuffer[] DATA = new GpuBuffer[ShadowPass.CASCADES];
	/** The cascade being drawn, or -1 outside the entity shadow pass. */
	private static int cascade = -1;
	private static boolean failed;
	private static boolean reported;
	private static int frameDraws;
	private static int lastDraws;

	private EntityShadows() {
	}

	/** Whether the entity shadow pass is drawing (the draw mixin then swaps or skips every draw). */
	public static boolean casting() {
		return cascade >= 0;
	}

	/** Whether this draw is one the shadow pass draws (an entity pipeline that writes depth, not emissive). */
	public static boolean casts(RenderPipeline pipeline) {
		if (!ENTITY_SHADER.equals(pipeline.getShaders().get(ShaderType.FRAGMENT))) {
			return false;
		}
		var defines = pipeline.getShaderDefines().flags();
		return pipeline.getDepthStencilState() != null && pipeline.getDepthStencilState().writeDepth()
				&& !defines.contains("EMISSIVE") && !defines.contains("GLINT");
	}

	public static boolean isVariant(RenderPipeline pipeline) {
		return SHADER.equals(pipeline.getShaders().get(ShaderType.FRAGMENT));
	}

	public static RenderPipeline variantOf(RenderPipeline vanilla) {
		return VARIANTS.computeIfAbsent(vanilla, EntityShadows::build);
	}

	private static RenderPipeline build(RenderPipeline vanilla) {
		return SodiumPipelineVariants.copy(vanilla,
						Identifier.fromNamespaceAndPath("bedrockvisuals", "entity_shadow/" + vanilla.getLocation().getPath()),
						SHADER)
				.withBindGroupLayout(SHADOW_DATA)
				.withColorTargetState(ShadowTerrainPipelines.UNUSED_COLOR)
				.withDepthStencilState(DepthStencilState.DEFAULT)
				.withCull(false)
				.build();
	}

	/** Binds the cascade's data on the pass the shadow variant is about to draw into. */
	public static void bind(RenderPass pass) {
		pass.setUniform("EntityShadowData", DATA[cascade]);
		frameDraws++;
	}

	/**
	 * Draws the frame's solid entities into each cascade, after the terrain's shadows.
	 *
	 * @param solidEntities Minecraft's executeSolid for this frame's prepared entity draws
	 * @param projection, modelView the camera matrices of this frame (the G-buffer's)
	 */
	static void render(Consumer<RenderPass> solidEntities, Matrix4fc projection, Matrix4fc modelView) {
		if (failed || solidEntities == null || !ShadowPass.isValid() || ShadowPass.unusedColorView() == null
				|| projection == null || modelView == null) {
			return;
		}
		Matrix4f inverseCamera = new Matrix4f(projection).mul(modelView).invert();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		frameDraws = 0;
		try {
			for (int c = 0; c < ShadowPass.CASCADES; c++) {
				if (DATA[c] == null) {
					int index = c;
					DATA[c] = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals entity shadow data " + index,
							GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, DATA_SIZE);
				}
				try (MemoryStack stack = MemoryStack.stackPush()) {
					encoder.writeToBuffer(DATA[c].slice(), Std140Builder.onStack(stack, DATA_SIZE)
							.putMat4f(ShadowPass.viewProjection(c)).putMat4f(inverseCamera).get());
				}
				int index = c;
				RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals entity shadows " + index)
						.withColorAttachment(ShadowPass.unusedColorView())
						.withDepthAttachment(ShadowPass.depthView(c), OptionalDouble.empty())
						.build();
				cascade = c;
				try (RenderPass pass = encoder.createRenderPass(descriptor)) {
					RenderSystem.bindDefaultUniforms(pass);
					solidEntities.accept(pass);
				} finally {
					cascade = -1;
				}
			}
			lastDraws = frameDraws;
			if (!reported && frameDraws > 0) {
				reported = true;
				BedrockVisualsClient.LOGGER.info("[shadows] entities drawn into the shadow cascades");
			}
		} catch (RuntimeException e) {
			cascade = -1;
			failed = true;
			BedrockVisualsClient.LOGGER.error("[shadows] entity shadows failed; entities cast none until restart", e);
		}
	}

	public static String status() {
		return failed ? "FAILED (see log)" : reported ? "ok, " + lastDraws + " entity draws into the cascades last frame"
				: "not drawn yet";
	}
}
