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
import dev.bedrockvisuals.bedrock.QualitySettings;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Bedrock's sky reflection probe: the sky (its radiance function, as SkyProbeDeferredShading lights the probe)
 * convolved per roughness into mips (10 at 512 texels), which reflections sample at mip (count - 1) × (1 - (1 - r)²) (ConvolutionType 1,
 * IBLParameters.y 10 in GPU captures). Bedrock keeps a cube; this keeps an octahedral map of the same 512 texels
 * and mips. Bedrock regenerates its probe now and then (reflection_probe_regen_configuration: every 600 frames,
 * 100 ticks, 500 blocks moved or a change in light) and blends the last two; the sky alone is cheap, so this
 * redraws it every few frames, and at once when the time jumps or the camera teleports.
 */
public final class SkyProbe {
	/** The most mips the probe can have (512 texels). */
	private static final int MAX_MIPS = 10;
	/**
	 * The probe's texels across, mips and filter samples: the quality preset's reflection level (Bedrock's
	 * reflection_configuration.pc.json ibl: 512 texels and 64 samples at ultra, 128 below, 64 down to 16 samples).
	 */
	private static int size = 512;
	private static int mips = MAX_MIPS;
	private static int samples = 64;
	/** Frames between redraws. */
	private static final int INTERVAL = 4;

	private static final BindGroupLayout BASE_LAYOUT = BindGroupLayout.builder()
			.withUniform("ProbeFace0", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeFace1", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeFace2", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeFace3", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeFace4", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeFace5", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeFaces", UniformType.UNIFORM_BUFFER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
			// Declared by common.glsl, which the shader includes; every declared block must be bound.
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("PreviousBase", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeBlend", UniformType.UNIFORM_BUFFER)
			.build();
	/**
	 * TUNE: how long a new scene capture takes to fade in over the last. Bedrock blends its last two probes
	 * (reflection_probe_regen_configuration); switched at once, reflections that fall to the probe jumped at every
	 * capture. Its blend's length isn't in the files.
	 */
	private static final long BLEND_NANOS = 1_000_000_000L;
	private static GpuTexture previousBase;
	private static GpuTextureView previousBaseView;
	private static GpuBuffer blendData;
	/** When the fade to the newest capture began; 0 when none is under way. */
	private static long blendStart;
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
	private static final GpuTextureView[] mipViews = new GpuTextureView[MAX_MIPS];
	private static final GpuBuffer[] mipData = new GpuBuffer[MAX_MIPS];
	private static int frame;
	private static boolean drawn;
	private static boolean failed;
	/** The base was redrawn and its mips haven't been filtered from it yet. */
	private static boolean mipsPending;

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
		// The scene probe's faces, when it captured terrain this frame (they are lit with this frame's data).
		SceneProbe.ensure();
		boolean newScene = SceneProbe.lightFaces(encoder, lightingData, atmosphereData);
		try {
			create(); // first, so a new preset's probe is drawn whole this frame
		} catch (RuntimeException e) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[sky] sky probe failed; reflections use the sky directly until restart", e);
			return;
		}
		// A new capture fades in over the last one (BLEND_NANOS): the base as it stands is kept, and until the fade
		// ends the probe is drawn every frame, mixing towards the new. A jump in time or a teleport switches at once.
		long nowNanos = System.nanoTime();
		if (newScene && drawn && !SettingsBlend.snapping()) {
			encoder.copyTextureToTexture(base, previousBase, 0, 0, 0, 0, 0, size, size);
			blendStart = nowNanos;
		} else if (SettingsBlend.snapping()) {
			blendStart = 0;
		}
		float blend = 1;
		if (blendStart != 0) {
			blend = Math.clamp((nowNanos - blendStart) / (float) BLEND_NANOS, 0f, 1f);
		}
		boolean fading = blendStart != 0;
		if (fading && blend >= 1) {
			blendStart = 0; // this frame draws the last step, the new capture alone
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			encoder.writeToBuffer(blendData.slice(), Std140Builder.onStack(stack, 16)
					.putVec4(blend, fading ? 1 : 0, 0, 0).get());
		}
		boolean now = !drawn || SettingsBlend.snapping() || newScene || fading;
		boolean due = now || ++frame % INTERVAL == 0;
		// The base and the mips filtered from it are drawn two frames apart, so each frame of the round carries
		// at most half the work (all at once, the GPU's frame time rose by some 0.5 ms every fourth frame). When
		// the probe is needed at once (the first, a jump in time, a teleport, a new scene), both are drawn now.
		boolean mipsDue = now || (mipsPending && frame % INTERVAL == INTERVAL / 2);
		if (!due && !mipsDue) {
			return;
		}
		try {
			create();
			if (due) {
				draw(encoder, baseView, "base", pass -> {
					pass.setPipeline(RenderSystem.getCompiledPipeline(BASE));
					SceneProbe.bindFaces(pass);
					pass.setUniform("LightingData", lightingData);
					pass.setUniform("AtmosphereData", atmosphereData);
					pass.setUniform("GradingData", LightingPass.gradingData());
					pass.setUniform("PreviousBase", previousBaseView,
							RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
					pass.setUniform("ProbeBlend", blendData);
				});
				mipsPending = true;
			}
			if (!mipsDue) {
				return;
			}
			mipsPending = false;
			for (int m = 0; m < mips; m++) {
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
		QualitySettings.Reflections reflections = Quality.reflections();
		int wanted = Integer.highestOneBit(Math.clamp(reflections.iblDimension(), 16, 512));
		if (probe != null && wanted == size && reflections.iblSamples() == samples) {
			return;
		}
		close();
		size = wanted;
		mips = Integer.numberOfTrailingZeros(size) + 1;
		samples = Math.max(1, reflections.iblSamples());
		drawn = false;
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
		base = device.createTexture(() -> "Bedrock Visuals sky probe base", usage | GpuTexture.USAGE_COPY_SRC,
				BloomPass.HDR_FORMAT, size, size, 1, 1);
		baseView = device.createTextureView(base);
		previousBase = device.createTexture(() -> "Bedrock Visuals sky probe previous base",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, BloomPass.HDR_FORMAT, size, size, 1, 1);
		previousBaseView = device.createTextureView(previousBase);
		if (blendData == null) {
			blendData = device.createBuffer(() -> "Bedrock Visuals sky probe blend", GpuBuffer.USAGE_UNIFORM
					| GpuBuffer.USAGE_COPY_DST, 16);
		}
		blendStart = 0;
		probe = device.createTexture(() -> "Bedrock Visuals sky probe", usage, BloomPass.HDR_FORMAT, size, size, 1, mips);
		probeView = device.createTextureView(probe);
		CommandEncoder encoder = device.createCommandEncoder();
		for (int m = 0; m < mips; m++) {
			mipViews[m] = device.createTextureView(probe, m, 1);
			int mip = m;
			mipData[m] = device.createBuffer(() -> "Bedrock Visuals sky probe mip " + mip,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
			// Mip m holds the roughness that samples it: (mips - 1) × (1 - (1 - r)²) = m.
			float roughness = (float) (1 - Math.sqrt(1 - m / (double) (mips - 1)));
			try (MemoryStack stack = MemoryStack.stackPush()) {
				encoder.writeToBuffer(mipData[m].slice(),
						Std140Builder.onStack(stack, 16).putVec4(roughness, Math.max(1, size >> m), samples, 0).get());
			}
		}
	}

	private static void close() {
		if (probe == null) {
			return;
		}
		for (int m = 0; m < MAX_MIPS; m++) {
			if (mipViews[m] != null) {
				mipViews[m].close();
				mipViews[m] = null;
			}
			if (mipData[m] != null) {
				mipData[m].close();
				mipData[m] = null;
			}
		}
		probeView.close();
		probe.close();
		baseView.close();
		base.close();
		previousBaseView.close();
		previousBase.close();
		previousBase = null;
		previousBaseView = null;
		probe = null;
		probeView = null;
		base = null;
		baseView = null;
	}

	/** The probe's texels across (the base pass's octahedral map is this size). */
	static int size() {
		return size;
	}

	/** The probe's mip count (the shaders read mip (count - 1) × (1 - (1 - r)²)). */
	static int mips() {
		return mips;
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
		return failed ? "FAILED (see log)" : drawn ? "on, " + size + " texels, " + mips + " mips, " + samples + " samples" : "not drawn yet";
	}
}
