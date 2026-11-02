package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;

/**
 * The frame's pre-exposure luminance for {@link AutoExposure}. The lighting pass writes it (terrain as lit,
 * sky from its colours) into a full-size R16 float target; every few frames it's copied to a buffer, and when
 * the copy completes the histogram is built from it on the CPU. Like Bedrock, the exposure lags a frame or
 * two behind what it measured.
 */
final class LuminanceMeter {
	static final GpuFormat FORMAT = GpuFormat.R16_FLOAT;
	/** Frames between measurements: about ten a second, plenty for an adaptation of about a second. */
	private static final int INTERVAL = 6;

	private static GpuTexture texture;
	private static GpuTextureView view;
	private static GpuBuffer readback;
	private static int width;
	private static int height;
	private static volatile boolean pending;
	private static int frame;
	private static boolean failed;

	private LuminanceMeter() {
	}

	/** The target for this frame, created or resized to match the main target. */
	static GpuTextureView target(int w, int h) {
		if (texture == null || w != width || h != height) {
			close();
			GpuDevice device = RenderSystem.getDevice();
			texture = device.createTexture(() -> "Bedrock Visuals luminance",
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC, FORMAT, w, h, 1, 1);
			view = device.createTextureView(texture);
			readback = device.createBuffer(() -> "Bedrock Visuals luminance readback",
					GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 2);
			width = w;
			height = h;
		}
		return view;
	}

	/** After the lighting pass: copies this frame's luminance for measuring, unless a copy is in flight. */
	static void measure() {
		if (failed || texture == null || pending || frame++ % INTERVAL != 0) {
			return;
		}
		pending = true;
		GpuBuffer buffer = readback;
		int w = width;
		int h = height;
		try {
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0, () -> {
				try (GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
					AutoExposure.accept(mapped.data(), w, h);
				} catch (RuntimeException e) {
					fail(e);
				} finally {
					pending = false;
				}
			}, 0);
		} catch (RuntimeException e) {
			pending = false;
			fail(e);
		}
	}

	private static void fail(RuntimeException e) {
		if (!failed) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[exposure] luminance readback failed; exposure stays fixed", e);
		}
	}

	static String status() {
		return failed ? "FAILED (see log)" : texture == null ? "not measured yet" : "ok";
	}

	private static void close() {
		if (view != null) {
			view.close();
			texture.close();
			// A copy still in flight reads this buffer from its callback; leave it to be collected then.
			if (!pending) {
				readback.close();
			}
			view = null;
			texture = null;
			readback = null;
		}
	}
}
