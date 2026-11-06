package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

/**
 * Bedrock-style water in Sodium's translucent pass. Minecraft draws translucent terrain with order-independent
 * transparency, in three stages (depth bounds, transmittance, accumulate), each with its own pipeline. Each
 * gets a variant with the same color targets, bind groups and defines plus the water inputs: the G-buffer's
 * depth (how deep the water is, and what reflections hit), a copy of the lit scene before translucency (what
 * lies beneath the water, and the reflections' colors), the frame's lighting and grading, and the water data. The
 * shader shades water fragments and passes every other translucent block through as Sodium would, and
 * Minecraft's OIT composite blends the result over the deferred-lit terrain.
 */
public final class WaterPass {
	static final BindGroupLayout INPUTS = LocalExposure.withInputs(BindGroupLayout.builder()
			.withUniform("u_GBufferDepth", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("u_SceneColor", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("WaterData", UniformType.UNIFORM_BUFFER)
			.withUniform("u_WaterNormal", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("u_WaterMers", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
			.withUniform("u_BrdfLut", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("u_ShadowMap3", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("u_CloudShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("WaterColumnMap", UniformType.COMBINED_IMAGE_SAMPLER)
			).build();
	/** std140 size of WaterData in water/terrain.fsh: five vec4s. */
	private static final int DATA_SIZE = 6 * 16;

	/*
	 * Bedrock's water extinction per block, per channel (WaterExtinctionCoefficients in GPU captures of the
	 * default water: 0.309041, 0.0610688, 0.0411471), which WaterExtinction applies along the view under the
	 * surface. All of it goes in ABSORPTION; the in-scattered light is the volumetric fog's (Underwater.java).
	 */
	private static final float[] ABSORPTION = {0.309041f, 0.0610688f, 0.0411471f};
	private static final float[] SCATTERING = {0f, 0f, 0f};

	/** Scattering per block, linear RGB. */
	static float[] scattering() {
		return SCATTERING.clone();
	}

	/** Extinction per block (absorption + scattering), linear RGB. */
	static float[] extinction() {
		return new float[] {ABSORPTION[0] + SCATTERING[0], ABSORPTION[1] + SCATTERING[1], ABSORPTION[2] + SCATTERING[2]};
	}

	private static final Map<RenderPipeline, RenderPipeline> VARIANTS = new ConcurrentHashMap<>();
	private static GpuBuffer data;
	/** The lit opaque scene, copied from the main target after the lighting pass. */
	private static GpuTexture scene;
	private static GpuTextureView sceneView;
	/** True between {@link #prepare} and the end of the frame's main pass, when the inputs are valid. */
	private static boolean ready;
	private static boolean failed;
	private static boolean reported;

	private WaterPass() {
	}

	/** Called after the lighting pass: whether this frame's water can be shaded, and its data written. */
	public static void prepare(TextureAtlas blockAtlas, RenderTarget main, boolean lit) {
		ready = false;
		if (failed || !lit || LightingPass.lightingData() == null || LightingPass.atmosphereData() == null) {
			return;
		}
		try {
			copyScene(main);
			if (data == null) {
				data = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals water data",
						GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, DATA_SIZE);
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Std140Builder builder = Std140Builder.onStack(stack, DATA_SIZE);
				putRect(builder, blockAtlas.getSprite(Identifier.withDefaultNamespace("block/water_still")));
				putRect(builder, blockAtlas.getSprite(Identifier.withDefaultNamespace("block/water_flow")));
				builder.putVec4(ABSORPTION[0], ABSORPTION[1], ABSORPTION[2], 0);
				builder.putVec4(SCATTERING[0], SCATTERING[1], SCATTERING[2], 1);
				// Surface map animation: Bedrock's flipbook advances a frame every 2 ticks, like Java's water.
				ClientLevel level = Minecraft.getInstance().level;
				long ticks = level == null ? 0 : level.getGameTime();
				int frames = WaterSurfaceMaps.frames();
				builder.putVec4((float) ((ticks / 2) % frames), frames, BrdfLut.view() != null ? 1 : 0, 0);
				// Water's side faces against see-through blocks (leaves, glass, seagrass) use water_overlay;
				// without it they were drawn as plain tinted texture, bright blue.
				putRect(builder, blockAtlas.getSprite(Identifier.withDefaultNamespace("block/water_overlay")));
				RenderSystem.getDevice().createCommandEncoder().writeToBuffer(data.slice(), builder.get());
			}
			ready = true;
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	private static void copyScene(RenderTarget main) {
		GpuTexture color = main.getColorTexture();
		if (scene == null || scene.getWidth(0) != main.width || scene.getHeight(0) != main.height
				|| scene.getFormat() != color.getFormat()) {
			if (scene != null) {
				sceneView.close();
				scene.close();
			}
			scene = RenderSystem.getDevice().createTexture(() -> "Bedrock Visuals scene before translucency",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, color.getFormat(), main.width,
					main.height, 1, 1);
			sceneView = RenderSystem.getDevice().createTextureView(scene);
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(color, scene, 0, 0, 0, 0, 0, main.width,
				main.height);
	}

	private static void putRect(Std140Builder builder, TextureAtlasSprite sprite) {
		builder.putVec4(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
	}

	/** Whether Sodium's translucent terrain should be drawn with the water variants this frame. */
	public static boolean isActive() {
		return ready && !failed;
	}

	/** Binds the water inputs on the render pass Sodium is about to draw translucent terrain into. */
	public static void bind(RenderPass pass) {
		GBuffer gbuffer = GBufferPass.gbuffer();
		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		pass.setUniform("u_GBufferDepth", gbuffer.depthView(), nearest);
		pass.setUniform("u_SceneColor", sceneView, nearest);
		pass.setUniform("LightingData", LightingPass.lightingData());
		pass.setUniform("GradingData", LightingPass.gradingData());
		pass.setUniform("WaterData", data);
		pass.setUniform("u_WaterNormal", WaterSurfaceMaps.normal(), nearest);
		pass.setUniform("u_WaterMers", WaterSurfaceMaps.mers(), nearest);
		pass.setUniform("AtmosphereData", LightingPass.atmosphereData());
		// Without Bedrock's table the shader approximates it (WaterData.Animation.z 0) and never reads this.
		pass.setUniform("u_BrdfLut", BrdfLut.view() != null ? BrdfLut.view() : WaterSurfaceMaps.mers(),
				RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
		// Without a shadow map or cloud cells this frame the shader skips them (ShadowParams.x,
		// CloudShadowField.w), but every declared sampler must be bound.
		GpuTextureView outer = ShadowPass.depthView(ShadowPass.CASCADES - 1);
		pass.setUniform("u_ShadowMap3", outer != null ? outer : gbuffer.depthView(), nearest);
		pass.setUniform("u_CloudShadowMap", CloudShadows.view() != null ? CloudShadows.view() : gbuffer.depthView(),
				RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
		pass.setUniform("WaterColumnMap", WaterColumns.view() != null ? WaterColumns.view() : gbuffer.depthView(), nearest);
		LocalExposure.bind(pass);
	}

	public static RenderPipeline variantOf(RenderPipeline sodium) {
		return VARIANTS.computeIfAbsent(sodium, WaterPass::build);
	}

	private static RenderPipeline build(RenderPipeline sodium) {
		RenderPipeline.Builder builder = SodiumPipelineVariants.copy(sodium, "water").withBindGroupLayout(INPUTS);
		// The OIT stages write different targets with their own blending; keep them exactly.
		List<ColorTargetState> targets = sodium.getColorTargetStates();
		for (int i = 0; i < targets.size(); i++) {
			builder.withColorTargetState(i, targets.get(i));
		}
		return builder.build();
	}

	/** The variant failed to build or compile: vanilla water until restart. */
	public static void fail(RuntimeException e) {
		if (!failed) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[water] water shading failed; vanilla water until restart", e);
		}
	}

	public static void reportFirstUse() {
		if (!reported) {
			reported = true;
			BedrockVisualsClient.LOGGER.info("[water] translucent terrain drawn with Bedrock water shading");
		}
	}

	/** A one-line summary for diagnostics. */
	public static String status() {
		return failed ? "FAILED (see log)" : reported ? "ok" : "not drawn yet";
	}
}
