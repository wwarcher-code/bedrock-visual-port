package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.EnumMap;
import java.util.Map;

/**
 * The deferred renderer's G-buffer: what each opaque pixel is made of, before any lighting.
 *
 * <p>All color targets are 8-bit RGBA, the main target's format, so the debug view can copy them to the
 * screen directly. Depth is the G-buffer's own: Minecraft 26.3 uses reversed depth (1 at the near plane),
 * so it is cleared to 0.
 */
public final class GBuffer {
	public enum Target {
		/** RGB: block color (texture times vertex tint and ambient occlusion), no lighting. A: unused. */
		ALBEDO("albedo"),
		/** RGB: world-space normal, mapped from [-1, 1] to [0, 1]. A: unused. */
		NORMAL("normal"),
		/** RGBA: metalness, emissive, roughness, subsurface (Bedrock's MERS layout). */
		MATERIAL("material"),
		/** R: block light, G: sky light, both from Minecraft's light levels. B, A: unused. */
		LIGHT("light");

		public final String label;

		Target(String label) {
			this.label = label;
		}
	}

	private static final int COLOR_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
			| GpuTexture.USAGE_COPY_SRC;

	private final Map<Target, GpuTexture> textures = new EnumMap<>(Target.class);
	private final Map<Target, GpuTextureView> views = new EnumMap<>(Target.class);
	private GpuTexture depth;
	private GpuTextureView depthView;
	private int width;
	private int height;

	/** Recreates the textures when the size changes; cheap when it doesn't. */
	public void ensureSize(int newWidth, int newHeight) {
		if (depth != null && width == newWidth && height == newHeight) {
			return;
		}
		close();
		width = newWidth;
		height = newHeight;
		GpuDevice device = RenderSystem.getDevice();
		for (Target target : Target.values()) {
			GpuTexture texture = device.createTexture(() -> "Bedrock Visuals G-buffer " + target.label, COLOR_USAGE,
					GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			textures.put(target, texture);
			views.put(target, device.createTextureView(texture));
		}
		depth = device.createTexture(() -> "Bedrock Visuals G-buffer depth",
				GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT,
				width, height, 1, 1);
		depthView = device.createTextureView(depth);
	}

	public GpuTexture texture(Target target) {
		return textures.get(target);
	}

	public GpuTextureView view(Target target) {
		return views.get(target);
	}

	public GpuTextureView depthView() {
		return depthView;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public void close() {
		views.values().forEach(GpuTextureView::close);
		textures.values().forEach(GpuTexture::close);
		views.clear();
		textures.clear();
		if (depthView != null) {
			depthView.close();
			depth.close();
			depthView = null;
			depth = null;
		}
	}
}
