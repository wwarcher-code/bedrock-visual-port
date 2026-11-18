package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

/**
 * Bedrock's temporal anti-aliasing: the TAAU pass of its Upscaling material, run at the window's resolution
 * (Bedrock runs it when its upscaling option is on; docs/bedrock-renderer-notes.md, "Temporal anti-aliasing").
 * Each frame the level is drawn with its projection shifted by a sub-pixel jitter; after the level (before the
 * hand and the HUD) the frame is blended into a history of earlier frames, reprojected by the camera's motion and
 * held to the colours around each pixel this frame, so edges and thin details average out over frames.
 *
 * <p>Bedrock reads per-pixel motion vectors from its G-buffer; this renderer has none, so the motion is rebuilt
 * from the depth buffer and the camera's movement (exact for the world, not for moving entities, which the
 * neighbourhood clip keeps from smearing). The tuning values Bedrock sets at run time (the neighbourhood box's
 * width at rest and in motion, clip or clamp, the motion dilation, the jitter pattern) aren't in its shaders and
 * no GPU capture with TAAU on exists yet: they're estimates, marked TUNE. F6 switches it off for comparison.
 */
public final class TemporalAA {
	private static final KeyMapping TOGGLE = new KeyMapping("key.bedrockvisuals.toggle_taa", InputConstants.KEY_F6,
			KeyMapping.Category.MISC);

	/** TUNE (TAAUpscalingParameters.y): the neighbourhood box's half-width in standard deviations, at rest. */
	private static float GAMMA_STILL = 1.25f;
	/** TUNE (TAAUpscalingParameters.x): the same for motion of a pixel or more a frame. */
	private static float GAMMA_MOVING = 0.75f;
	/**
	 * TUNE (TAAUpscalingParameters.z): 1 clips the history towards this frame's colour, 0 clamps it. Clamped: on
	 * Minecraft's pixel textures the clip, which pulls the history towards this frame's jittered sample, left the
	 * still picture flickering (a mean frame-to-frame difference of 3.3 against 0.1 clamped, the same as no TAA).
	 */
	private static float CLIP = 0;
	/** TUNE (TAAUpscalingParameters.w): 1 takes the longest motion of the 3x3 neighbourhood. */
	private static final float DILATE = 1;
	/** TUNE (ResolutionRatiosAndFPEpsilon.z): below this a colour difference counts as parallel to a box face. */
	private static final float EPSILON = 1e-4f;
	/** TUNE: jitter phases (Halton 2, 3). */
	private static final int PHASES = 8;
	private static final GpuFormat HISTORY_FORMAT = GpuFormat.RGBA16_FLOAT;
	private static final int DATA_SIZE = 3 * 64 + 5 * 16;

	private static final BindGroupLayout INPUTS = BindGroupLayout.builder()
			.withUniform("FinalColor", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("SceneDepth", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("History", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("TAAData", UniformType.UNIFORM_BUFFER)
			.build();
	private static final RenderPipeline RESOLVE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/taa_resolve"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "post/taa"))
			.withBindGroupLayout(INPUTS)
			.withColorTargetState(0, new ColorTargetState(Optional.empty(), ColorTargetState.DEFAULT.format(),
					ColorTargetState.WRITE_ALL))
			.withColorTargetState(1, new ColorTargetState(Optional.empty(), HISTORY_FORMAT, ColorTargetState.WRITE_ALL))
			.withDepthStencilState(Optional.empty())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();

	private static boolean enabled = true;
	private static boolean failed;
	/** Whether this frame was drawn jittered (beginFrame decided), so resolve runs. */
	private static boolean active;
	private static boolean historyValid;
	private static int phase;
	private static float jitterX;
	private static float jitterY;
	private static CameraRenderState jitteredCamera;
	private static Matrix4f originalProjection;

	private static GpuTexture[] history = new GpuTexture[2];
	private static GpuTextureView[] historyViews = new GpuTextureView[2];
	private static int current;
	private static GpuTexture colorCopy;
	private static GpuTextureView colorCopyView;
	private static int width;
	private static int height;
	private static GpuBuffer data;
	private static Matrix4f previousViewProjection;
	private static Vec3 previousCamera;
	private static long frames;

	private TemporalAA() {
	}

	public static void register() {
		KeyMappingHelper.registerKeyMapping(TOGGLE);
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (TOGGLE.consumeClick()) {
				setEnabled(!enabled);
				if (client.player != null) {
					client.player.sendOverlayMessage(Component.literal("Temporal anti-aliasing: " + (enabled ? "on" : "off")));
				}
			}
		});
	}

	public static void setEnabled(boolean on) {
		enabled = on;
		historyValid = false;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	private static int debugMode;

	/** For tuning runs (ScriptRunner's taatune line): the box widths at rest and in motion, and clip or clamp. */
	public static void tune(float still, float moving, float clip) {
		GAMMA_STILL = still;
		GAMMA_MOVING = moving;
		CLIP = clip;
	}

	/** 0 normal; 1 this frame's sample alone; 2 the motion; 3 the history read (the shader's debug modes). */
	public static void setDebugMode(int mode) {
		debugMode = mode;
	}

	/**
	 * At the start of GameRenderer.renderLevel: when TAA runs this frame, the camera's projection is swapped for
	 * a jittered copy, which everything in the level draws with (Minecraft's, Sodium's and this renderer's
	 * passes all take it from there). {@link #resolve} puts the original back.
	 */
	public static void beginFrame(CameraRenderState camera) {
		active = false;
		RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (!enabled || failed || camera == null || camera.projectionMatrix == null || main == null
				|| !DeferredRenderer.isEnabled() || GBufferDebugView.view() != GBufferDebugView.View.OFF
				|| FlawlessFrames.isExporting()) {
			historyValid = false;
			return;
		}
		phase = (phase + 1) % PHASES;
		// Halton (2, 3) in [-0.5, 0.5) pixels.
		jitterX = halton(phase + 1, 2) - 0.5f;
		jitterY = halton(phase + 1, 3) - 0.5f;
		originalProjection = camera.projectionMatrix;
		// Shifts clip space by the jitter in pixels: x' = x + 2 jx / width × w.
		camera.projectionMatrix = new Matrix4f().translation(2f * jitterX / main.width, 2f * jitterY / main.height, 0)
				.mul(originalProjection);
		jitteredCamera = camera;
		active = true;
	}

	/**
	 * After the level, before the hand and the HUD: puts the camera's projection back and blends the frame into
	 * the history.
	 */
	public static void resolve(RenderTarget main) {
		if (jitteredCamera != null) {
			jitteredCamera.projectionMatrix = originalProjection;
			jitteredCamera = null;
		}
		if (!active) {
			return;
		}
		active = false;
		if (!DeferredRenderer.litThisFrame()) {
			historyValid = false;
			return;
		}
		try {
			ensureTargets(main);
			frames++;
			// The frame's matrices as the level drew with them (view bobbing included), and without the jitter.
			Matrix4f jittered = new Matrix4f(GBufferPass.projection()).mul(GBufferPass.modelView());
			Matrix4f unjitter = new Matrix4f().translation(-2f * jitterX / main.width, -2f * jitterY / main.height, 0);
			Matrix4f viewProjection = new Matrix4f(unjitter).mul(jittered);
			Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
			if (SettingsBlend.snapping() || previousViewProjection == null) {
				historyValid = false; // a teleport or a jump in time: nothing to blend with
			}
			if (previousViewProjection == null) {
				previousViewProjection = new Matrix4f(viewProjection);
				previousCamera = camera;
			}
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Std140Builder builder = Std140Builder.onStack(stack, DATA_SIZE);
				builder.putMat4f(new Matrix4f(jittered).invert());
				builder.putMat4f(viewProjection);
				builder.putMat4f(previousViewProjection);
				builder.putVec4((float) (camera.x - previousCamera.x), (float) (camera.y - previousCamera.y),
						(float) (camera.z - previousCamera.z), 0);
				// SubPixelJitterAndValidHistory, TAAUpscalingParameters, ResolutionRatiosAndFPEpsilon (the
				// display and render resolutions are the same here), RenderResolution.
				builder.putVec4(jitterX, jitterY, historyValid ? 1 : 0, debugMode);
				builder.putVec4(GAMMA_MOVING, GAMMA_STILL, CLIP, DILATE);
				builder.putVec4(1, 1, EPSILON, 0);
				builder.putVec4(main.width, main.height, 1f / main.width, 1f / main.height);
				encoder.writeToBuffer(data.slice(), builder.get());
			}
			previousViewProjection.set(viewProjection);
			previousCamera = camera;
			encoder.copyTextureToTexture(main.getColorTexture(), colorCopy, 0, 0, 0, 0, 0, main.width, main.height);
			int previous = current;
			current = 1 - current;
			RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals TAA")
					.withColorAttachment(main.getColorTextureView(), Optional.empty())
					.withColorAttachment(historyViews[current], Optional.empty())
					.build();
			try (RenderPass pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(RESOLVE));
				pass.setUniform("FinalColor", colorCopyView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				pass.setUniform("SceneDepth", main.getDepthTextureView(),
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				pass.setUniform("History", historyViews[previous],
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.setUniform("TAAData", data);
				pass.draw(3, 1, 0, 0);
			}
			historyValid = true;
		} catch (RuntimeException e) {
			failed = true;
			historyValid = false;
			BedrockVisualsClient.LOGGER.error("[taa] temporal anti-aliasing failed; off until restart", e);
		}
	}

	private static void ensureTargets(RenderTarget main) {
		GpuDevice device = RenderSystem.getDevice();
		if (data == null) {
			data = device.createBuffer(() -> "Bedrock Visuals TAA data", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
					DATA_SIZE);
		}
		if (history[0] != null && width == main.width && height == main.height) {
			return;
		}
		close();
		for (int i = 0; i < 2; i++) {
			int index = i;
			history[i] = device.createTexture(() -> "Bedrock Visuals TAA history " + index,
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, HISTORY_FORMAT, main.width,
					main.height, 1, 1);
			historyViews[i] = device.createTextureView(history[i]);
		}
		colorCopy = device.createTexture(() -> "Bedrock Visuals TAA input", GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_COPY_DST, main.getColorTexture().getFormat(), main.width, main.height, 1, 1);
		colorCopyView = device.createTextureView(colorCopy);
		width = main.width;
		height = main.height;
		historyValid = false;
	}

	private static void close() {
		for (int i = 0; i < 2; i++) {
			if (historyViews[i] != null) {
				historyViews[i].close();
				history[i].close();
				historyViews[i] = null;
				history[i] = null;
			}
		}
		if (colorCopyView != null) {
			colorCopyView.close();
			colorCopy.close();
			colorCopyView = null;
			colorCopy = null;
		}
	}

	private static float halton(int index, int base) {
		float result = 0;
		float f = 1;
		while (index > 0) {
			f /= base;
			result += f * (index % base);
			index /= base;
		}
		return result;
	}

	public static String status() {
		return failed ? "FAILED (see log)" : !enabled ? "off (F6)" : frames == 0 ? "on, not run yet"
				: "on, " + PHASES + " jitter phases";
	}
}
