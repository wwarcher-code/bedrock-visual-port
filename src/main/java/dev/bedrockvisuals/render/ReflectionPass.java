package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Bedrock's screen-space reflection passes (ScreenSpaceReflections: SSRRayMarch, SSRFillGaps, SSRGetReflectedColor)
 * for the water seen from above, as full-screen passes between the lighting pass and the water: the march finds
 * the water surface on each pixel's view (from the water column map; Bedrock draws it in its WaterPrepass) and
 * marches its mirror ray, the gap fill closes single misses from the hits above and below (ExtendedGapFill), and
 * the resolve reads the HDR scene at each hit and accumulates over frames, clamped to the neighbourhood
 * (SSRTemporalAccumulationParams (1, 0.5, 2, 0.03125)). The water pass reads the result in place of its own
 * per-fragment march.
 */
public final class ReflectionPass {
	/** SSRTemporalAccumulationParams in GPU captures: on, the clamp's scale, the radius, the new frame's weight. */
	private static final float[] TEMPORAL = {1f, 0.5f, 2f, 0.03125f};

	private static final BindGroupLayout MARCH_LAYOUT = BindGroupLayout.builder()
			.withUniform("Depth", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("DistantDepth", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("WaterColumnMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("u_WaterNormal", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("DistantData", UniformType.UNIFORM_BUFFER)
			.withUniform("WaterData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final BindGroupLayout FILL_LAYOUT = BindGroupLayout.builder()
			.withUniform("March", UniformType.COMBINED_IMAGE_SAMPLER)
			.build();
	private static final BindGroupLayout RESOLVE_LAYOUT = BindGroupLayout.builder()
			.withUniform("March", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Scene", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("History", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("ReflectionData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final RenderPipeline MARCH = pipeline("march", MARCH_LAYOUT);
	private static final RenderPipeline FILL = pipeline("fill", FILL_LAYOUT);
	private static final RenderPipeline RESOLVE = pipeline("resolve", RESOLVE_LAYOUT);

	private static GpuTexture march;
	private static GpuTextureView marchView;
	private static GpuTexture filled;
	private static GpuTextureView filledView;
	private static final GpuTexture[] resolved = new GpuTexture[2];
	private static final GpuTextureView[] resolvedViews = new GpuTextureView[2];
	private static GpuBuffer data;
	/** Bound in place of the reflections when there are none: 1 texel marked "no water" (alpha -2). */
	private static GpuTexture placeholder;
	private static GpuTextureView placeholderView;
	private static int width;
	private static int height;
	/** Which resolve buffer holds this frame's result; the other is last frame's. */
	private static int current;
	private static boolean historyValid;
	private static boolean ready;
	private static boolean failed;

	private ReflectionPass() {
	}

	private static RenderPipeline pipeline(String name, BindGroupLayout layout) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/reflection_" + name))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "reflection/" + name))
				.withBindGroupLayout(layout)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), BloomPass.HDR_FORMAT, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(Optional.empty())
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
	}

	/** After the lighting pass and WaterPass.prepare, before the water is drawn. */
	static void render(GBuffer gbuffer) {
		ready = false;
		ensurePlaceholder();
		if (failed || LightingPass.lightingData() == null || BloomPass.hdrView() == null
				|| WaterPass.data() == null) {
			return;
		}
		try {
			create(gbuffer.width(), gbuffer.height());
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				encoder.writeToBuffer(data.slice(), Std140Builder.onStack(stack, 32)
						.putVec4(TEMPORAL[0], TEMPORAL[1], TEMPORAL[2], TEMPORAL[3])
						.putVec4(historyValid && !SettingsBlend.snapping() ? 1 : 0, 0, 0, 0).get());
			}
			GpuTextureView distant = LightingPass.distantDepth() != null ? LightingPass.distantDepth() : gbuffer.depthView();
			draw(encoder, marchView, "march", pass -> {
				pass.setPipeline(RenderSystem.getCompiledPipeline(MARCH));
				pass.setUniform("Depth", gbuffer.depthView(), nearest);
				pass.setUniform("DistantDepth", distant, nearest);
				pass.setUniform("WaterColumnMap", WaterColumns.view() != null ? WaterColumns.view() : gbuffer.depthView(), nearest);
				pass.setUniform("u_WaterNormal", WaterSurfaceMaps.normal(), nearest);
				pass.setUniform("LightingData", LightingPass.lightingData());
				pass.setUniform("GradingData", LightingPass.gradingData());
				pass.setUniform("DistantData", LightingPass.distantData());
				pass.setUniform("WaterData", WaterPass.data());
			});
			draw(encoder, filledView, "fill", pass -> {
				pass.setPipeline(RenderSystem.getCompiledPipeline(FILL));
				pass.setUniform("March", marchView, nearest);
			});
			int previous = current;
			current = 1 - current;
			GpuTextureView history = resolvedViews[previous];
			draw(encoder, resolvedViews[current], "resolve", pass -> {
				pass.setPipeline(RenderSystem.getCompiledPipeline(RESOLVE));
				pass.setUniform("March", filledView, nearest);
				pass.setUniform("Scene", BloomPass.hdrView(), linear);
				pass.setUniform("History", history, linear);
				pass.setUniform("LightingData", LightingPass.lightingData());
				pass.setUniform("GradingData", LightingPass.gradingData());
				pass.setUniform("ReflectionData", data);
			});
			historyValid = true;
			ready = true;
		} catch (RuntimeException e) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[water] reflection passes failed; the water marches its own reflections", e);
		}
	}

	private static void draw(CommandEncoder encoder, GpuTextureView target, String label, Consumer<RenderPass> setup) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals reflections " + label)
				.withColorAttachment(target, Optional.of(new Vector4f(0, 0, 0, -2)))
				.build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			setup.accept(pass);
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void create(int newWidth, int newHeight) {
		if (march != null && width == newWidth && height == newHeight) {
			return;
		}
		close();
		width = newWidth;
		height = newHeight;
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
		march = device.createTexture(() -> "Bedrock Visuals reflection march", usage, BloomPass.HDR_FORMAT, width, height, 1, 1);
		marchView = device.createTextureView(march);
		filled = device.createTexture(() -> "Bedrock Visuals reflection march, filled", usage, BloomPass.HDR_FORMAT,
				width, height, 1, 1);
		filledView = device.createTextureView(filled);
		for (int i = 0; i < 2; i++) {
			int index = i;
			resolved[i] = device.createTexture(() -> "Bedrock Visuals reflections " + index, usage, BloomPass.HDR_FORMAT,
					width, height, 1, 1);
			resolvedViews[i] = device.createTextureView(resolved[i]);
		}
		if (data == null) {
			data = device.createBuffer(() -> "Bedrock Visuals reflection data", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 32);
		}
		historyValid = false;
	}

	private static void close() {
		if (march == null) {
			return;
		}
		marchView.close();
		march.close();
		filledView.close();
		filled.close();
		for (int i = 0; i < 2; i++) {
			resolvedViews[i].close();
			resolved[i].close();
		}
		march = null;
	}

	/**
	 * This frame's reflections for the water pass, or a texel marked "no water" when there are none (it marches
	 * its own then).
	 */
	static GpuTextureView view() {
		return ready && !failed ? resolvedViews[current] : placeholderView;
	}

	private static void ensurePlaceholder() {
		if (placeholder != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		placeholder = device.createTexture(() -> "Bedrock Visuals no reflections",
				GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, BloomPass.HDR_FORMAT, 1, 1, 1, 1);
		placeholderView = device.createTextureView(placeholder);
		RenderPassDescriptor clear = RenderPassDescriptor.builder(() -> "Bedrock Visuals no reflections")
				.withColorAttachment(placeholderView, Optional.of(new Vector4f(0, 0, 0, -2))).build();
		try (RenderPass pass = device.createCommandEncoder().createRenderPass(clear)) {
			// Cleared by opening it.
		}
	}

	public static String status() {
		return failed ? "FAILED (see log)" : ready ? "on, temporal accumulation and gap fill" : "not drawn";
	}
}
