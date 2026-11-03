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
import com.mojang.renderpearl.api.pipeline.BlendFunction;
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
 * Bedrock's bloom (the Bloom material; renderer/shared_config/bloom_configuration.shared.json): the HDR scene is
 * thresholded into a half-resolution image, blurred by dual-filter downsampling and upsampling, and added to
 * the scene before exposure as {@code + intensity × bloom}, intensity 0.02. Here the scene is the lighting
 * pass's HDR output, and the difference the bloom makes after tone mapping is added to the main image.
 */
public final class BloomPass {
	/** bloom_configuration.shared.json intensity. */
	static final float INTENSITY = 0.02f;
	/** BloomParams.y in GPU captures: 0, so Bedrock blooms the whole image, not only bright parts. */
	static final float THRESHOLD = 0f;
	/** TUNE: dual-filter levels below the half-resolution image (the engine's count isn't in the shaders). */
	private static final int LEVELS = 5;
	static final GpuFormat HDR_FORMAT = GpuFormat.RGBA16_FLOAT;

	private static final BindGroupLayout SOURCE = BindGroupLayout.builder()
			.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
			.build();
	private static final BindGroupLayout THRESHOLD_INPUTS = BindGroupLayout.builder()
			.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("BloomData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final BindGroupLayout BLEND_INPUTS = BindGroupLayout.builder()
			.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Scene", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Main", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("BloomData", UniformType.UNIFORM_BUFFER)
			.build();

	private static final RenderPipeline THRESHOLD_PIPELINE = pipeline("threshold", THRESHOLD_INPUTS, Optional.empty());
	private static final RenderPipeline DOWN_PIPELINE = pipeline("down", SOURCE, Optional.empty());
	private static final RenderPipeline UP_PIPELINE = pipeline("up", SOURCE, Optional.empty());
	/**
	 * Writes the main image plus the bloom, read from a copy of the image: an additive blend onto the main image
	 * may be lost the way local exposure's multiply blend was (LocalExposure).
	 */
	private static final RenderPipeline BLEND_PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/bloom_blend"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "bloom/blend"))
			.withBindGroupLayout(BLEND_INPUTS)
			.withColorTargetState(0, new ColorTargetState(Optional.empty(), ColorTargetState.DEFAULT.format(),
					ColorTargetState.WRITE_ALL))
			.withDepthStencilState(Optional.empty())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();
	private static GpuTexture mainCopy;
	private static GpuTextureView mainCopyView;

	private static GpuTexture hdr;
	private static GpuTextureView hdrView;
	private static final GpuTexture[] down = new GpuTexture[LEVELS + 1];
	private static final GpuTextureView[] downViews = new GpuTextureView[LEVELS + 1];
	private static final GpuTexture[] up = new GpuTexture[LEVELS + 1];
	private static final GpuTextureView[] upViews = new GpuTextureView[LEVELS + 1];
	private static GpuTexture previous;
	private static GpuTextureView previousView;
	private static boolean previousValid;
	private static GpuBuffer data;
	private static int width;
	private static int height;
	private static boolean failed;

	private BloomPass() {
	}

	private static RenderPipeline pipeline(String name, BindGroupLayout inputs, Optional<BlendFunction> blend) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/bloom_" + name))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "bloom/" + name))
				.withBindGroupLayout(inputs)
				.withColorTargetState(0, new ColorTargetState(blend,
						blend.isPresent() ? ColorTargetState.DEFAULT.format() : HDR_FORMAT, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(Optional.empty())
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
	}

	/** The lighting pass's HDR target, created or resized to match the main target. */
	static GpuTextureView hdrTarget(int w, int h) {
		if (hdr == null || w != width || h != height) {
			close();
			GpuDevice device = RenderSystem.getDevice();
			hdr = device.createTexture(() -> "Bedrock Visuals HDR scene",
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC,
					HDR_FORMAT, w, h, 1, 1);
			hdrView = device.createTextureView(hdr);
			previous = device.createTexture(() -> "Bedrock Visuals previous HDR scene",
					GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, HDR_FORMAT, w, h, 1, 1);
			previousView = device.createTextureView(previous);
			previousValid = false;
			int lw = w;
			int lh = h;
			for (int i = 0; i <= LEVELS; i++) {
				lw = Math.max(1, lw / 2);
				lh = Math.max(1, lh / 2);
				int level = i;
				down[i] = device.createTexture(() -> "Bedrock Visuals bloom down " + level,
						GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, HDR_FORMAT, lw, lh, 1, 1);
				downViews[i] = device.createTextureView(down[i]);
				up[i] = device.createTexture(() -> "Bedrock Visuals bloom up " + level,
						GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, HDR_FORMAT, lw, lh, 1, 1);
				upViews[i] = device.createTextureView(up[i]);
			}
			width = w;
			height = h;
		}
		return hdrView;
	}

	/** The lighting pass's HDR target, or null before the first frame. */
	static GpuTextureView hdrView() {
		return hdrView;
	}

	/**
	 * After the lighting pass and local exposure: blur the HDR scene's bright parts and add them to the main
	 * image.
	 *
	 * @param scene the HDR scene after local exposure
	 */
	static void render(RenderTarget main, GpuTextureView scene) {
		if (failed || scene == null || LightingPass.lightingData() == null) {
			return;
		}
		try {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			if (data == null) {
				data = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals bloom data",
						GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Std140Builder builder = Std140Builder.onStack(stack, 16);
				builder.putVec4(INTENSITY, THRESHOLD, AutoExposure.exposure(), 0);
				encoder.writeToBuffer(data.slice(), builder.get());
			}
			GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
			draw(encoder, "threshold", downViews[0], THRESHOLD_PIPELINE, pass -> {
				pass.setUniform("Source", scene, linear);
				pass.setUniform("BloomData", data);
			});
			for (int i = 1; i <= LEVELS; i++) {
				GpuTextureView source = downViews[i - 1];
				draw(encoder, "down", downViews[i], DOWN_PIPELINE, pass -> pass.setUniform("Source", source, linear));
			}
			// Up the chain from the smallest level: each upsample reads the level below (first the smallest
			// downsample, then the previous upsample).
			GpuTextureView previous = downViews[LEVELS];
			for (int i = LEVELS - 1; i >= 0; i--) {
				GpuTextureView source = previous;
				draw(encoder, "up", upViews[i], UP_PIPELINE, pass -> pass.setUniform("Source", source, linear));
				previous = upViews[i];
			}
			GpuTextureView bloom = upViews[0];
			if (mainCopy == null || mainCopy.getWidth(0) != main.width || mainCopy.getHeight(0) != main.height) {
				if (mainCopy != null) {
					mainCopyView.close();
					mainCopy.close();
				}
				mainCopy = RenderSystem.getDevice().createTexture(() -> "Bedrock Visuals bloom main copy",
						GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, main.getColorTexture().getFormat(),
						main.width, main.height, 1, 1);
				mainCopyView = RenderSystem.getDevice().createTextureView(mainCopy);
			}
			encoder.copyTextureToTexture(main.getColorTexture(), mainCopy, 0, 0, 0, 0, 0, main.width, main.height);
			RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals bloom blend")
					.withColorAttachment(main.getColorTextureView(), Optional.empty())
					.build();
			try (RenderPass pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(BLEND_PIPELINE));
				pass.setUniform("Source", bloom, linear);
				pass.setUniform("Main", mainCopyView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				pass.setUniform("Scene", scene, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				pass.setUniform("LightingData", LightingPass.lightingData());
				pass.setUniform("GradingData", LightingPass.gradingData());
				pass.setUniform("BloomData", data);
				pass.draw(3, 1, 0, 0);
			}
		} catch (RuntimeException e) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[bloom] bloom failed; off until restart", e);
		}
	}

	private interface Binder {
		void bind(RenderPass pass);
	}

	private static void draw(CommandEncoder encoder, String label, GpuTextureView target, RenderPipeline pipeline,
			Binder binder) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals bloom " + label)
				.withColorAttachment(target, Optional.empty())
				.build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			binder.bind(pass);
			pass.draw(3, 1, 0, 0);
		}
	}

	/** Copies this frame's HDR scene for the next frame's screen-space reflections. */
	static void keepForNextFrame() {
		if (hdr == null) {
			return;
		}
		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(hdr, previous, 0, 0, 0, 0, 0, width, height);
		previousValid = true;
	}

	/** The previous frame's HDR scene, or null before there is one. */
	static GpuTextureView previousHdr() {
		return previousValid ? previousView : null;
	}

	public static String status() {
		return failed ? "FAILED (see log)" : "intensity " + INTENSITY + ", threshold " + THRESHOLD;
	}

	private static void close() {
		if (hdr != null) {
			hdrView.close();
			hdr.close();
			for (int i = 0; i <= LEVELS; i++) {
				downViews[i].close();
				down[i].close();
				upViews[i].close();
				up[i].close();
			}
			previousView.close();
			previous.close();
			hdr = null;
			hdrView = null;
			previousValid = false;
		}
	}
}
