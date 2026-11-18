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
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.compat.FlawlessFrames;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;

/**
 * The offline export preset (design doc, "Offline export"), on while Flashback exports (FlawlessFrames):
 * <ul>
 * <li>each output frame is the average of {@link #samples()} sub-frames: the level, extracted once, is drawn that
 * many times with sub-pixel jitter (Halton 2, 3) and averaged before the hand and the HUD go on top. TAA is off;
 * the accumulation is the anti-aliasing.</li>
 * <li>every frame is complete: what is staggered over frames while playing (the shadow cascades, the reflection
 * probe) is drawn whole on the first sub-frame, and the other sub-frames reuse it (nothing in the level moves
 * between them).</li>
 * <li>the renderer's animations run on the replay's timeline, not the wall clock (RenderClock).</li>
 * </ul>
 * Flashback itself steps the replay one frame of the export's frame rate per output frame and renders into a
 * target at the export's resolution (4K included), which is where the average is written.
 */
public final class OfflineRender {
	/** Sub-frames per output frame unless set otherwise (/bv offline). */
	public static final int DEFAULT_SAMPLES = 8;
	public static final int MAX_SAMPLES = 64;

	private static final GpuFormat AVERAGE_FORMAT = GpuFormat.RGBA16_FLOAT;
	private static final BindGroupLayout INPUTS = BindGroupLayout.builder()
			.withUniform("Current", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Average", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("AccumulateData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final RenderPipeline ACCUMULATE = pipeline("accumulate", AVERAGE_FORMAT);
	private static final RenderPipeline WRITE_BACK = pipeline("accumulate_write_back", ColorTargetState.DEFAULT.format());

	private static int samples = DEFAULT_SAMPLES;
	/** Whether the preset runs without an export (/bv offline, ScriptRunner's offline line), for testing. */
	private static boolean forced;
	/** Debugging: the frame shows its last sub-frame alone instead of the average (ScriptRunner's offline last). */
	private static boolean lastOnly;
	private static int subframe;
	private static int subframes = 1;
	private static boolean failed;

	private static final GpuTexture[] average = new GpuTexture[2];
	private static final GpuTextureView[] averageViews = new GpuTextureView[2];
	private static int current;
	private static GpuTexture colorCopy;
	private static GpuTextureView colorCopyView;
	private static GpuBuffer data;
	private static int width;
	private static int height;

	private OfflineRender() {
	}

	private static RenderPipeline pipeline(String name, GpuFormat format) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/offline_" + name))
				.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
				.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "post/accumulate"))
				.withBindGroupLayout(INPUTS)
				.withColorTargetState(0, new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL))
				.withDepthStencilState(Optional.empty())
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
	}

	/** Whether the offline preset is on: an export is running, or it was switched on for testing. */
	public static boolean active() {
		return FlawlessFrames.isExporting() || forced;
	}

	public static void setForced(boolean on) {
		forced = on;
	}

	public static void setLastOnly(boolean on) {
		lastOnly = on;
	}

	public static int samples() {
		return samples;
	}

	public static void setSamples(int count) {
		samples = Math.clamp(count, 1, MAX_SAMPLES);
	}

	/** How many times this frame's level is drawn: the samples while the preset is on. */
	public static int subframesThisFrame() {
		return active() && !failed ? samples : 1;
	}

	/** Before each draw of the level in a frame (GameRendererMixin). */
	public static void beginSubframe(int index, int count) {
		subframe = index;
		subframes = count;
	}

	/** After the frame's last draw of the level. */
	public static void endFrame() {
		subframe = 0;
		subframes = 1;
	}

	/** The sub-frame being drawn (0 outside the preset). */
	static int subframe() {
		return subframe;
	}

	/** Whether the frame's level is being drawn more than once (TemporalAA jitters it then). */
	static boolean accumulating() {
		return subframes > 1;
	}

	/** Whether this is a repeat draw of the frame's level: its staggered work is already done. */
	static boolean laterSubframe() {
		return subframes > 1 && subframe > 0;
	}

	/** This sub-frame's jitter in pixels, in [-0.5, 0.5): Halton (2, 3), the first at the pixel's centre. */
	static float jitterX() {
		return subframe == 0 ? 0f : TemporalAA.halton(subframe, 2) - 0.5f;
	}

	static float jitterY() {
		return subframe == 0 ? 0f : TemporalAA.halton(subframe, 3) - 0.5f;
	}

	/**
	 * After the level, before the hand and the HUD: this sub-frame joins the running average, and after the last
	 * the average replaces the frame.
	 */
	static void accumulate(RenderTarget main) {
		if (subframes <= 1 || failed) {
			return;
		}
		try {
			ensureTargets(main);
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			encoder.copyTextureToTexture(main.getColorTexture(), colorCopy, 0, 0, 0, 0, 0, main.width, main.height);
			int previous = current;
			current = 1 - current;
			writeWeight(encoder, lastOnly ? 1f : 1f / (subframe + 1));
			draw(encoder, ACCUMULATE, averageViews[current], colorCopyView, averageViews[previous], "accumulate");
			if (subframe == subframes - 1) {
				writeWeight(encoder, 0f);
				draw(encoder, WRITE_BACK, main.getColorTextureView(), averageViews[current], averageViews[current],
						"write back");
			}
		} catch (RuntimeException e) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[offline] sub-frame accumulation failed; exports draw one sample a frame",
					e);
		}
	}

	private static void writeWeight(CommandEncoder encoder, float weight) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			encoder.writeToBuffer(data.slice(), Std140Builder.onStack(stack, 16).putVec4(weight, 0, 0, 0).get());
		}
	}

	private static void draw(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView target,
			GpuTextureView currentView, GpuTextureView averageView, String label) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals offline " + label)
				.withColorAttachment(target, Optional.empty())
				.build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			pass.setUniform("Current", currentView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.setUniform("Average", averageView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			pass.setUniform("AccumulateData", data);
			pass.draw(3, 1, 0, 0);
		}
	}

	private static void ensureTargets(RenderTarget main) {
		GpuDevice device = RenderSystem.getDevice();
		if (data == null) {
			data = device.createBuffer(() -> "Bedrock Visuals offline accumulation data",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16);
		}
		if (average[0] != null && width == main.width && height == main.height) {
			return;
		}
		close();
		for (int i = 0; i < 2; i++) {
			int index = i;
			average[i] = device.createTexture(() -> "Bedrock Visuals offline average " + index,
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, AVERAGE_FORMAT, main.width,
					main.height, 1, 1);
			averageViews[i] = device.createTextureView(average[i]);
		}
		colorCopy = device.createTexture(() -> "Bedrock Visuals offline sub-frame", GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_COPY_DST, main.getColorTexture().getFormat(), main.width, main.height, 1, 1);
		colorCopyView = device.createTextureView(colorCopy);
		width = main.width;
		height = main.height;
	}

	private static void close() {
		for (int i = 0; i < 2; i++) {
			if (averageViews[i] != null) {
				averageViews[i].close();
				average[i].close();
				averageViews[i] = null;
				average[i] = null;
			}
		}
		if (colorCopyView != null) {
			colorCopyView.close();
			colorCopy.close();
			colorCopyView = null;
			colorCopy = null;
		}
	}

	/** A one-line summary for diagnostics. */
	public static String status() {
		return (failed ? "FAILED (see log); " : "") + (active() ? "on" + (forced ? " (forced)" : " (exporting)") : "off")
				+ ", " + samples + " sub-frames a frame";
	}
}
