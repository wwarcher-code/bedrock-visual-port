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
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Bedrock's sky reflection probe: the sky (its radiance function, as SkyProbeDeferredShading lights the probe)
 * convolved per roughness into 10 mips, which reflections sample at mip 9 × (1 - (1 - r)²) (ConvolutionType 1,
 * IBLParameters.y 10 in GPU captures). Bedrock keeps a cube; this keeps an octahedral map of the same 512 texels
 * and mips. Bedrock regenerates its probe now and then (reflection_probe_regen_configuration: every 600 frames,
 * 100 ticks, 500 blocks moved or a change in light) and blends the last two; the sky alone is cheap, so this
 * redraws it every few frames, and at once when the time jumps or the camera teleports.
 */
public final class SkyProbe {
	static final int SIZE = 512;
	static final int MIPS = 10;
	private static final int SAMPLES = 96;
	/** Frames between redraws. */
	private static final int INTERVAL = 4;

	private static final BindGroupLayout BASE_LAYOUT = BindGroupLayout.builder()
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
			// Declared by common.glsl, which the shader includes; every declared block must be bound.
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final BindGroupLayout FILTER_LAYOUT = BindGroupLayout.builder()
			.withUniform("ProbeBase", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final RenderPipeline BASE = pipeline("base", BASE_LAYOUT);
	private static final RenderPipeline FILTER = pipeline("filter", FILTER_LAYOUT);

	private static GpuTexture base;
	private static GpuTextureView baseView;
	private static GpuTexture probe;
	private static GpuTextureView probeView;
	private static final GpuTextureView[] mipViews = new GpuTextureView[MIPS];
	private static final GpuBuffer[] mipData = new GpuBuffer[MIPS];
	private static int frame;
	private static boolean drawn;
	private static boolean failed;

	private SkyProbe() {
	}

	private static RenderPipeline pipeline(String name, BindGroupLayout layout) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/sky_probe_" + name))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "sky/probe_" + name))
				.withBindGroupLayout(layout)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), BloomPass.HDR_FORMAT,
						ColorTargetState.WRITE_ALL))
				.withDepthStencilState(Optional.empty())
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
	}

	/**
	 * Redraws the probe when it's due. Called with the frame's lighting and atmosphere data written and no
	 * render pass open.
	 */
	static void render(CommandEncoder encoder, GpuBuffer lightingData, GpuBuffer atmosphereData, boolean skyOn) {
		if (failed || !skyOn) {
			return;
		}
		boolean due = !drawn || ++frame % INTERVAL == 0 || SettingsBlend.snapping();
		if (!due) {
			return;
		}
		try {
			create();
			draw(encoder, baseView, "base", pass -> {
				pass.setPipeline(RenderSystem.getCompiledPipeline(BASE));
				pass.setUniform("LightingData", lightingData);
				pass.setUniform("AtmosphereData", atmosphereData);
				pass.setUniform("GradingData", LightingPass.gradingData());
			});
			for (int m = 0; m < MIPS; m++) {
				int mip = m;
				draw(encoder, mipViews[m], "mip " + m, pass -> {
					pass.setPipeline(RenderSystem.getCompiledPipeline(FILTER));
					pass.setUniform("ProbeBase", baseView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
					pass.setUniform("ProbeData", mipData[mip]);
				});
			}
			drawn = true;
		} catch (RuntimeException e) {
			failed = true;
			drawn = false;
			BedrockVisualsClient.LOGGER.error("[sky] sky probe failed; reflections use the sky directly until restart", e);
		}
	}

	private static void draw(CommandEncoder encoder, GpuTextureView target, String label, Consumer<RenderPass> setup) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals sky probe " + label)
				.withColorAttachment(target, Optional.of(new Vector4f(0)))
				.build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			setup.accept(pass);
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void create() {
		if (probe != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
		base = device.createTexture(() -> "Bedrock Visuals sky probe base", usage, BloomPass.HDR_FORMAT, SIZE, SIZE, 1, 1);
		baseView = device.createTextureView(base);
		probe = device.createTexture(() -> "Bedrock Visuals sky probe", usage, BloomPass.HDR_FORMAT, SIZE, SIZE, 1, MIPS);
		probeView = device.createTextureView(probe);
		CommandEncoder encoder = device.createCommandEncoder();
		for (int m = 0; m < MIPS; m++) {
			mipViews[m] = device.createTextureView(probe, m, 1);
			int mip = m;
			mipData[m] = device.createBuffer(() -> "Bedrock Visuals sky probe mip " + mip,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
			// Mip m holds the roughness that samples it: 9 × (1 - (1 - r)²) = m.
			float roughness = (float) (1 - Math.sqrt(1 - m / 9.0));
			try (MemoryStack stack = MemoryStack.stackPush()) {
				encoder.writeToBuffer(mipData[m].slice(),
						Std140Builder.onStack(stack, 16).putVec4(roughness, Math.max(1, SIZE >> m), SAMPLES, 0).get());
			}
		}
	}

	/** Whether there is a probe to read (the shaders fall back to the sky directly otherwise). */
	static boolean ready() {
		return drawn && !failed;
	}

	/** The probe with all its mips, or null before the first. */
	static GpuTextureView view() {
		return probeView;
	}

	public static String status() {
		return failed ? "FAILED (see log)" : drawn ? "on, " + SIZE + " texels, " + MIPS + " mips" : "not drawn yet";
	}
}
