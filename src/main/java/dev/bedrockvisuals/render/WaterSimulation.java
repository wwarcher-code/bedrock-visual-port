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
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Bedrock's FFT ocean (the WaterSimulation material): InitSpectrum fills a Phillips spectrum, UpdateSpectrum
 * turns it to this moment, and log2(N) HorizontalFFT then VerticalFFT Stockham stages turn it into a height
 * field, at its properties' defaults (G 9.81, WaterPatchSize 512, WaterResolution 512, WaterWaveDampening 100,
 * WaterWindAlignment 0.5, WaterWindVelocity (0, 2)).
 *
 * <p>Nothing in Bedrock 1.26.60 reads the result: no other material samples it and the game never names the
 * material, and its water's waves come from the surface pass's sine octaves (WaterPass, water_waves.glsl). So
 * it runs only for the "water simulation" debug view, which shows its height field in the screen's corner.
 */
public final class WaterSimulation {
	private static final int N = 512;
	private static final int STAGES = 9; // log2(N)
	private static final float G = 9.81f;
	private static final float PATCH = 512f;
	private static final float DAMPENING = 100f;
	private static final float ALIGNMENT = 0.5f;
	private static final float WIND_X = 0f;
	private static final float WIND_Y = 2f;
	private static final int DATA_SIZE = 3 * 16;
	private static final long START = System.nanoTime();

	private static final BindGroupLayout INIT_LAYOUT = BindGroupLayout.builder()
			.withUniform("SimData", UniformType.UNIFORM_BUFFER).build();
	private static final BindGroupLayout UPDATE_LAYOUT = BindGroupLayout.builder()
			.withUniform("SimData", UniformType.UNIFORM_BUFFER)
			.withUniform("Spectrum", UniformType.COMBINED_IMAGE_SAMPLER).build();
	private static final BindGroupLayout FFT_LAYOUT = BindGroupLayout.builder()
			.withUniform("SimData", UniformType.UNIFORM_BUFFER)
			.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER).build();
	private static final BindGroupLayout SHOW_LAYOUT = BindGroupLayout.builder()
			.withUniform("Height", UniformType.COMBINED_IMAGE_SAMPLER).build();
	private static final RenderPipeline INIT = pipeline("init", INIT_LAYOUT, GpuFormat.RGBA32_FLOAT);
	private static final RenderPipeline UPDATE = pipeline("update", UPDATE_LAYOUT, GpuFormat.RGBA32_FLOAT);
	private static final RenderPipeline FFT = pipeline("fft", FFT_LAYOUT, GpuFormat.RGBA32_FLOAT);
	private static final RenderPipeline SHOW = pipeline("show", SHOW_LAYOUT, GpuFormat.RGBA8_UNORM);

	private static GpuTexture spectrum;
	private static GpuTextureView spectrumView;
	private static final GpuTexture[] ping = new GpuTexture[2];
	private static final GpuTextureView[] pingViews = new GpuTextureView[2];
	private static GpuTexture shown;
	private static GpuTextureView shownView;
	private static GpuBuffer frameData;
	private static final GpuBuffer[] stageData = new GpuBuffer[2 * STAGES];
	private static boolean initialised;
	private static boolean failed;

	private WaterSimulation() {
	}

	private static RenderPipeline pipeline(String name, BindGroupLayout layout, GpuFormat format) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/water_sim_" + name))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "water_sim/" + name))
				.withBindGroupLayout(layout)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(Optional.empty())
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
	}

	/** For the debug view, after Minecraft's main pass: steps the simulation and draws it in the corner. */
	static void showOn(RenderTarget main) {
		if (failed || main.width < N || main.height < N) {
			return;
		}
		try {
			create();
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			float seconds = (System.nanoTime() - START) / 1e9f;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				encoder.writeToBuffer(frameData.slice(), data(stack, seconds, 0, 0));
			}
			if (!initialised) {
				draw(encoder, spectrumView, "init", pass -> {
					pass.setPipeline(RenderSystem.getCompiledPipeline(INIT));
					pass.setUniform("SimData", frameData);
				});
				initialised = true;
			}
			draw(encoder, pingViews[0], "update", pass -> {
				pass.setPipeline(RenderSystem.getCompiledPipeline(UPDATE));
				pass.setUniform("SimData", frameData);
				pass.setUniform("Spectrum", spectrumView, nearest);
			});
			for (int stage = 0; stage < 2 * STAGES; stage++) {
				GpuTextureView source = pingViews[stage % 2];
				GpuBuffer stageBuffer = stageData[stage];
				draw(encoder, pingViews[1 - stage % 2], "fft " + stage, pass -> {
					pass.setPipeline(RenderSystem.getCompiledPipeline(FFT));
					pass.setUniform("SimData", stageBuffer);
					pass.setUniform("Source", source, nearest);
				});
			}
			// An even number of stages: the height field is back in the first buffer.
			draw(encoder, shownView, "show", pass -> {
				pass.setPipeline(RenderSystem.getCompiledPipeline(SHOW));
				pass.setUniform("Height", pingViews[0], nearest);
			});
			encoder.copyTextureToTexture(shown, main.getColorTexture(), 0, 0, 0, 0, 0, N, N);
		} catch (RuntimeException e) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[water] the FFT water simulation failed", e);
		}
	}

	private static java.nio.ByteBuffer data(MemoryStack stack, float seconds, float subSize, float horizontal) {
		return Std140Builder.onStack(stack, DATA_SIZE)
				.putVec4(G, PATCH, N, DAMPENING)
				.putVec4(WIND_X, WIND_Y, ALIGNMENT, seconds)
				.putVec4(subSize, horizontal, 0, 0).get();
	}

	private static void draw(CommandEncoder encoder, GpuTextureView target, String label, Consumer<RenderPass> setup) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals water simulation " + label)
				.withColorAttachment(target, Optional.of(new Vector4f(0, 0, 0, 0)))
				.build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			setup.accept(pass);
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void create() {
		if (spectrum != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
		spectrum = device.createTexture(() -> "Bedrock Visuals water spectrum", usage, GpuFormat.RGBA32_FLOAT, N, N, 1, 1);
		spectrumView = device.createTextureView(spectrum);
		for (int i = 0; i < 2; i++) {
			int index = i;
			ping[i] = device.createTexture(() -> "Bedrock Visuals water simulation " + index, usage,
					GpuFormat.RGBA32_FLOAT, N, N, 1, 1);
			pingViews[i] = device.createTextureView(ping[i]);
		}
		shown = device.createTexture(() -> "Bedrock Visuals water simulation, shown",
				usage | GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, N, N, 1, 1);
		shownView = device.createTextureView(shown);
		int usageBuffer = GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST;
		frameData = device.createBuffer(() -> "Bedrock Visuals water simulation data", usageBuffer, DATA_SIZE);
		CommandEncoder encoder = device.createCommandEncoder();
		for (int stage = 0; stage < 2 * STAGES; stage++) {
			stageData[stage] = device.createBuffer(() -> "Bedrock Visuals water FFT stage", usageBuffer, DATA_SIZE);
			float subSize = 1 << (stage % STAGES + 1);
			float horizontal = stage < STAGES ? 1 : 0;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				encoder.writeToBuffer(stageData[stage].slice(), data(stack, 0, subSize, horizontal));
			}
		}
	}

	public static String status() {
		return failed ? "FAILED (see log)" : initialised ? "ran for the debug view" : "idle (debug view only; Bedrock reads none of it)";
	}
}
