package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
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
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

/**
 * Bedrock's local exposure (deferred_lighting_configuration.shared.json: on), after the lighting pass and
 * before the bloom, as Bedrock orders them. Verified in its shaders (docs/bedrock-renderer-notes.md, "Local
 * exposure"):
 * <ol>
 * <li>BuildBilateralGrid: each pixel's normalized log luminance is added into a grid of screen cells by
 * luminance slices;</li>
 * <li>FilterBilateralGrid: a Gaussian over the cells within each slice;</li>
 * <li>GaussianDownsample: a plain Gaussian blur of the log luminance;</li>
 * <li>ApplyLocalExposure: each pixel's level moves a quarter of the way from its surroundings' towards the
 * average, keeping its detail, with LocalExposureParams (0.75, 1, 0.4) from GPU captures.</li>
 * </ol>
 * The runtime sizes are from a GPU capture at 1920×1009: the grid is built from the scene at a quarter of the
 * resolution (480×252), in cells of 32 of those pixels (GridDimensions 15, 7, 32), filtered over 6 cells
 * (BilateralFilterParams), and the blur (GaussianBlurParams radius 12, step 1) is written at a sixteenth of
 * the resolution (120×63).
 */
public final class LocalExposure {
	/** LocalExposureParams from the captures: base kept, detail kept, share of the plain blur in the base. */
	static final float BASE_KEPT = 0.75f;
	static final float DETAIL_KEPT = 1f;
	static final float BLUR_SHARE = 0.4f;
	/** Quarter-resolution pixels per grid cell, luminance slices, filter radius in cells (captured). */
	private static final int CELL_PIXELS = 32;
	private static final int SLICES = 32;
	private static final int FILTER_RADIUS = 6;
	/** GaussianBlurParams radius and step (captured), in quarter-resolution pixels. */
	private static final int BLUR_RADIUS = 12;
	private static final int BLUR_STEP = 1;

	private static final BindGroupLayout SOURCE = BindGroupLayout.builder()
			.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LocalExposureData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final BindGroupLayout APPLY = BindGroupLayout.builder()
			.withUniform("Scene", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Main", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Blurred", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Grid", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("LocalExposureData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final GpuFormat LOG_FORMAT = GpuFormat.R16_FLOAT;
	private static final GpuFormat GRID_FORMAT = GpuFormat.RG16_FLOAT;

	private static final RenderPipeline LOG = pipeline("log", SOURCE, LOG_FORMAT);
	private static final RenderPipeline BUILD = pipeline("build", SOURCE, GRID_FORMAT);
	private static final RenderPipeline FILTER = pipeline("filter", SOURCE, GRID_FORMAT);
	private static final RenderPipeline BLUR = pipeline("blur", SOURCE, LOG_FORMAT);
	/**
	 * Writes the main image times the change, read from a copy of it, and the exposed HDR scene. Not a multiply
	 * blend (DST_COLOR, ZERO): in game that left the image untouched, while the same draw without blending
	 * landed.
	 */
	private static final RenderPipeline APPLY_PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/local_exposure_apply"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "exposure/apply"))
			.withBindGroupLayout(APPLY)
			.withColorTargetState(0, new ColorTargetState(Optional.empty(), ColorTargetState.DEFAULT.format(),
					ColorTargetState.WRITE_ALL))
			.withColorTargetState(1, new ColorTargetState(Optional.empty(), BloomPass.HDR_FORMAT, ColorTargetState.WRITE_ALL))
			.withDepthStencilState(Optional.empty())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();

	/** The main image as the lighting pass left it (with Java's sun, moon and stars under the sky). */
	private static Target mainCopy;

	private static Target logLuminance;
	private static Target grid;
	private static Target filtered;
	private static Target blurred;
	private static Target exposed;
	private static GpuBuffer data;
	private static int width;
	private static int height;
	private static boolean failed;
	private static boolean applied;

	private LocalExposure() {
	}

	private record Target(GpuTexture texture, GpuTextureView view) {
		void close() {
			view.close();
			texture.close();
		}
	}

	private static RenderPipeline pipeline(String name, BindGroupLayout inputs, GpuFormat format) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/local_exposure_" + name))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "exposure/" + name))
				.withBindGroupLayout(inputs)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(Optional.empty())
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
	}

	/** 480 / 32 = 15 and 252 / 32 = 7 in the capture: whole cells of the quarter-resolution scene. */
	private static int gridWidth() {
		return Math.max(1, Math.max(1, width / 4) / CELL_PIXELS);
	}

	private static int gridHeight() {
		return Math.max(1, Math.max(1, height / 4) / CELL_PIXELS);
	}

	private static GpuFormat mainFormat;

	private static void resize(int w, int h, GpuFormat format) {
		if (logLuminance != null && w == width && h == height && format == mainFormat) {
			return;
		}
		close();
		mainFormat = format;
		width = w;
		height = h;
		int quarterW = Math.max(1, w / 4);
		int quarterH = Math.max(1, h / 4);
		logLuminance = target("log luminance", LOG_FORMAT, quarterW, quarterH);
		blurred = target("blurred log luminance", LOG_FORMAT, Math.max(1, w / 16), Math.max(1, h / 16));
		grid = target("grid", GRID_FORMAT, gridWidth() * SLICES, gridHeight());
		filtered = target("filtered grid", GRID_FORMAT, gridWidth() * SLICES, gridHeight());
		exposed = target("exposed scene", BloomPass.HDR_FORMAT, w, h);
		GpuDevice device = RenderSystem.getDevice();
		GpuTexture copy = device.createTexture(() -> "Bedrock Visuals local exposure main copy",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, mainFormat, w, h, 1, 1);
		mainCopy = new Target(copy, device.createTextureView(copy));
	}

	private static Target target(String label, GpuFormat format, int w, int h) {
		GpuDevice device = RenderSystem.getDevice();
		GpuTexture texture = device.createTexture(() -> "Bedrock Visuals local exposure " + label,
				GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, format, w, h, 1, 1);
		return new Target(texture, device.createTextureView(texture));
	}

	/**
	 * After the lighting pass: applies local exposure to the main image.
	 *
	 * @return the locally exposed HDR scene for the bloom, or the lighting pass's own when this is off
	 */
	static GpuTextureView render(RenderTarget main, GpuTextureView scene) {
		applied = false;
		if (failed || scene == null || LightingPass.lightingData() == null) {
			return scene;
		}
		try {
			resize(main.width, main.height, main.getColorTexture().getFormat());
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			if (data == null) {
				data = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals local exposure data",
						GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 48);
			}
			// The unclamped adapted average, as Bedrock's AverageLuminance texture holds it.
			float average = Math.max(AutoExposure.adaptedLuminance(), 1e-6f);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Std140Builder builder = Std140Builder.onStack(stack, 48);
				builder.putVec4(BASE_KEPT, DETAIL_KEPT, BLUR_SHARE, (float) (Math.log(average) / Math.log(2)));
				builder.putVec4(gridWidth(), gridHeight(), SLICES, FILTER_RADIUS);
				builder.putVec4(BLUR_RADIUS, BLUR_STEP, 0, AutoExposure.UNIT_SCALE);
				encoder.writeToBuffer(data.slice(), builder.get());
			}
			GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			draw(encoder, "log luminance", logLuminance.view, LOG, pass -> pass.setUniform("Source", scene, linear));
			draw(encoder, "grid", grid.view, BUILD, pass -> pass.setUniform("Source", logLuminance.view, nearest));
			draw(encoder, "grid filter", filtered.view, FILTER, pass -> pass.setUniform("Source", grid.view, nearest));
			draw(encoder, "blur", blurred.view, BLUR, pass -> pass.setUniform("Source", logLuminance.view, linear));
			encoder.copyTextureToTexture(main.getColorTexture(), mainCopy.texture, 0, 0, 0, 0, 0, main.width, main.height);
			RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals local exposure")
					.withColorAttachment(main.getColorTextureView(), Optional.empty())
					.withColorAttachment(exposed.view, Optional.empty())
					.build();
			try (RenderPass pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(APPLY_PIPELINE));
				pass.setUniform("Main", mainCopy.view, nearest);
				pass.setUniform("Scene", scene, nearest);
				pass.setUniform("Blurred", blurred.view, linear);
				pass.setUniform("Grid", filtered.view, linear);
				pass.setUniform("LightingData", LightingPass.lightingData());
				pass.setUniform("GradingData", LightingPass.gradingData());
				pass.setUniform("LocalExposureData", data);
				pass.draw(3, 1, 0, 0);
			}
			applied = true;
			return exposed.view;
		} catch (RuntimeException e) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[exposure] local exposure failed; off until restart", e);
			return scene;
		}
	}

	private interface Binder {
		void bind(RenderPass pass);
	}

	private static void draw(CommandEncoder encoder, String label, GpuTextureView target, RenderPipeline pipeline,
			Binder binder) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals local exposure " + label)
				.withColorAttachment(target, Optional.empty())
				.build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			binder.bind(pass);
			pass.setUniform("LocalExposureData", data);
			pass.draw(3, 1, 0, 0);
		}
	}

	public static String status() {
		return failed ? "FAILED (see log)" : (applied ? "on" : "not run yet") + ", params (" + BASE_KEPT + ", "
				+ DETAIL_KEPT + ", " + BLUR_SHARE + "), grid " + gridWidth() + "x" + gridHeight() + "x" + SLICES;
	}

	private static void close() {
		for (Target target : new Target[] {logLuminance, blurred, grid, filtered, exposed, mainCopy}) {
			if (target != null) {
				target.close();
			}
		}
		logLuminance = null;
	}
}
