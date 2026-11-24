package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.BedrockPack;
import dev.bedrockvisuals.bedrock.MaterialAtlasImage;
import dev.bedrockvisuals.bedrock.Rgba;
import dev.bedrockvisuals.bedrock.TextureMap;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * The GPU copy of {@link MaterialAtlasImage}: Bedrock's material maps at the same texture coordinates as
 * Java's block atlas, so the G-buffer shader reads material and color with one UV. Rebuilt whenever Minecraft
 * recreates the block atlas (resource reloads); without a Bedrock pack, or if building fails, a single texel
 * of Bedrock's default fallback (fully rough) stands in.
 */
public final class MaterialAtlas {
	private static final Rgba ROUGH = new Rgba(0, 0, 1, 0);

	private static GpuTexture texture;
	private static GpuTextureView view;
	/** The block atlas texture the current material atlas was built for. */
	private static GpuTexture builtFor;
	private static GpuTextureView placeholder;
	/** Set when building fails; the fallback material is used until restart and the error is logged once. */
	private static boolean failed;
	private static String status = "not built yet";
	/** Level 0's emissive channel (MERS green), kept for finding emissive quads on the CPU (raytrace/EmissiveLights). */
	private static byte[] emissive;
	private static int emissiveWidth;
	private static int emissiveHeight;
	/** Counts rebuilds, so users of {@link #emissive} can tell when it changed. */
	private static int generation;

	private MaterialAtlas() {
	}

	public static GpuTextureView view(TextureAtlas blockAtlas) {
		Optional<BedrockPack> pack = BedrockInputs.pack();
		GpuTexture atlasTexture = blockAtlas.getTexture();
		if (pack.isEmpty() || atlasTexture == null) {
			return placeholder();
		}
		if (atlasTexture != builtFor && !failed) {
			try {
				rebuild(blockAtlas, atlasTexture, pack.get(), BedrockInputs.textureMap());
			} catch (RuntimeException e) {
				failed = true;
				close();
				BedrockVisualsClient.LOGGER.error("[materials] building the material atlas failed; blocks use the "
						+ "fallback material until restart", e);
				status = "FAILED: " + e;
			}
		}
		return view != null ? view : placeholder();
	}

	private static void rebuild(TextureAtlas blockAtlas, GpuTexture atlasTexture, BedrockPack pack, TextureMap map) {
		long start = System.nanoTime();
		builtFor = atlasTexture;
		close();

		int width = atlasTexture.getWidth(0);
		int height = atlasTexture.getHeight(0);
		List<MaterialAtlasImage.Slot> slots = new ArrayList<>();
		TextureAtlasSprite missing = blockAtlas.missingSprite();
		for (String name : map.javaTextures()) {
			TextureAtlasSprite sprite = blockAtlas.getSprite(Identifier.withDefaultNamespace("block/" + name));
			if (sprite != missing) {
				// getX and getY are the corner of the sprite's padded cell; its pixels start where its UVs do.
				int x = Math.round(sprite.getU0() * width);
				int y = Math.round(sprite.getV0() * height);
				slots.add(new MaterialAtlasImage.Slot(name, x, y, sprite.contents().width(),
						sprite.contents().height(), x - sprite.getX()));
			}
		}
		MaterialAtlasImage.Result image = MaterialAtlasImage.build(width, height, atlasTexture.getMipLevels(), slots,
				name -> map.textureSetFor(pack, name), pack.pbrFallback().blocks());

		int[] base = image.levels().getFirst();
		byte[] channel = new byte[width * height];
		for (int i = 0; i < channel.length; i++) {
			channel[i] = (byte) (base[i] >> 8);
		}
		emissive = channel;
		emissiveWidth = width;
		emissiveHeight = height;
		generation++;

		GpuDevice device = RenderSystem.getDevice();
		texture = device.createTexture(() -> "Bedrock Visuals material atlas",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1,
				image.levels().size());
		view = device.createTextureView(texture);
		CommandEncoder encoder = device.createCommandEncoder();
		for (int level = 0; level < image.levels().size(); level++) {
			upload(encoder, image.levels().get(level), level, Math.max(width >> level, 1), Math.max(height >> level, 1));
		}

		image.problems().stream().limit(10).forEach(p -> BedrockVisualsClient.LOGGER.warn("[materials] {}", p));
		status = String.format("%dx%d, %d mip levels, sprite padding %d, %d of %d block sprites from Bedrock texture sets, %d problems",
				width, height, image.levels().size(), slots.isEmpty() ? 0 : slots.getFirst().padding(), image.filledSlots(),
				slots.size(), image.problems().size());
		BedrockVisualsClient.LOGGER.info("[materials] built {}x{} material atlas with {} mip levels in {} ms: "
						+ "{} of {} block sprites from Bedrock texture sets", width, height, image.levels().size(),
				(System.nanoTime() - start) / 1_000_000, image.filledSlots(), slots.size());
	}

	/**
	 * The emissive texels in a rectangle of the block atlas (UVs, either order): their mean emissive (0 to 1), or 0
	 * without a material atlas.
	 */
	public static float emissiveMean(float u0, float v0, float u1, float v1) {
		byte[] channel = emissive;
		if (channel == null) {
			return 0;
		}
		int x0 = Math.clamp((int) Math.floor(Math.min(u0, u1) * emissiveWidth + 0.01), 0, emissiveWidth - 1);
		int x1 = Math.clamp((int) Math.ceil(Math.max(u0, u1) * emissiveWidth - 0.01), x0 + 1, emissiveWidth);
		int y0 = Math.clamp((int) Math.floor(Math.min(v0, v1) * emissiveHeight + 0.01), 0, emissiveHeight - 1);
		int y1 = Math.clamp((int) Math.ceil(Math.max(v0, v1) * emissiveHeight - 0.01), y0 + 1, emissiveHeight);
		int sum = 0;
		for (int y = y0; y < y1; y++) {
			int row = y * emissiveWidth;
			for (int x = x0; x < x1; x++) {
				sum += channel[row + x] & 0xFF;
			}
		}
		return sum / 255f / ((x1 - x0) * (y1 - y0));
	}

	/**
	 * The share of the texels in an atlas rectangle (texture coordinates) with any emissive at all, however faint:
	 * glow lichen's berries are marked at 5 of 255 in Bedrock's maps, the leaves not at all.
	 */
	public static float emissiveCoverage(float u0, float v0, float u1, float v1) {
		byte[] channel = emissive;
		if (channel == null) {
			return 0;
		}
		int x0 = Math.clamp((int) Math.floor(Math.min(u0, u1) * emissiveWidth + 0.01), 0, emissiveWidth - 1);
		int x1 = Math.clamp((int) Math.ceil(Math.max(u0, u1) * emissiveWidth - 0.01), x0 + 1, emissiveWidth);
		int y0 = Math.clamp((int) Math.floor(Math.min(v0, v1) * emissiveHeight + 0.01), 0, emissiveHeight - 1);
		int y1 = Math.clamp((int) Math.ceil(Math.max(v0, v1) * emissiveHeight - 0.01), y0 + 1, emissiveHeight);
		int lit = 0;
		for (int y = y0; y < y1; y++) {
			int row = y * emissiveWidth;
			for (int x = x0; x < x1; x++) {
				if (channel[row + x] != 0) {
					lit++;
				}
			}
		}
		return lit / (float) ((x1 - x0) * (y1 - y0));
	}

	/**
	 * The atlas texel (its index, y times the width plus x, plus one) in a rectangle (texture coordinates) that
	 * Bedrock marks as glowing most, 0 if none is: the colour glow lichen's light takes (its berries').
	 */
	public static int brightestEmissiveTexel(float u0, float v0, float u1, float v1) {
		byte[] channel = emissive;
		if (channel == null) {
			return 0;
		}
		int x0 = Math.clamp((int) Math.floor(Math.min(u0, u1) * emissiveWidth + 0.01), 0, emissiveWidth - 1);
		int x1 = Math.clamp((int) Math.ceil(Math.max(u0, u1) * emissiveWidth - 0.01), x0 + 1, emissiveWidth);
		int y0 = Math.clamp((int) Math.floor(Math.min(v0, v1) * emissiveHeight + 0.01), 0, emissiveHeight - 1);
		int y1 = Math.clamp((int) Math.ceil(Math.max(v0, v1) * emissiveHeight - 0.01), y0 + 1, emissiveHeight);
		int best = 0;
		int bestIndex = 0;
		for (int y = y0; y < y1; y++) {
			int row = y * emissiveWidth;
			for (int x = x0; x < x1; x++) {
				int value = channel[row + x] & 0xFF;
				if (value > best) {
					best = value;
					bestIndex = row + x + 1;
				}
			}
		}
		return bestIndex;
	}

	/** One texel's emissive (0 to 1) in the block atlas's pixels; 0 outside it or before it is built. */
	public static float emissiveAt(int x, int y) {
		byte[] channel = emissive;
		if (channel == null || x < 0 || y < 0 || x >= emissiveWidth || y >= emissiveHeight) {
			return 0;
		}
		return (channel[y * emissiveWidth + x] & 0xFF) / 255f;
	}

	/** The emissive channel's size in texels (0 before it is built). */
	public static int emissiveWidth() {
		return emissiveWidth;
	}

	public static int emissiveHeight() {
		return emissiveHeight;
	}

	/** Changes when the material atlas is rebuilt. */
	public static int generation() {
		return generation;
	}

	/** The current material atlas on the GPU, or null before it is built. */
	public static GpuTextureView currentView() {
		return view;
	}

	/** A one-line summary of the last build, for diagnostics. */
	public static String status() {
		return BedrockInputs.pack().isEmpty() ? "no Bedrock pack; fallback material" : status;
	}

	private static void upload(CommandEncoder encoder, int[] argb, int level, int width, int height) {
		ByteBuffer data = MemoryUtil.memAlloc(width * height * 4);
		try {
			for (int i = 0; i < width * height; i++) {
				int value = argb[i];
				data.put((byte) (value >> 16)).put((byte) (value >> 8)).put((byte) value).put((byte) (value >>> 24));
			}
			data.flip();
			encoder.writeToTexture(texture, data, level, 0, 0, 0, width, height);
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private static GpuTextureView placeholder() {
		if (placeholder == null) {
			GpuDevice device = RenderSystem.getDevice();
			GpuTexture single = device.createTexture(() -> "Bedrock Visuals material placeholder",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			placeholder = device.createTextureView(single);
			ByteBuffer data = MemoryUtil.memAlloc(4);
			try {
				data.put((byte) Math.round(ROUGH.r() * 255)).put((byte) Math.round(ROUGH.g() * 255))
						.put((byte) Math.round(ROUGH.b() * 255)).put((byte) Math.round(ROUGH.a() * 255)).flip();
				device.createCommandEncoder().writeToTexture(single, data, 0, 0, 0, 0, 1, 1);
			} finally {
				MemoryUtil.memFree(data);
			}
		}
		return placeholder;
	}

	private static void close() {
		if (view != null) {
			view.close();
			texture.close();
			view = null;
			texture = null;
		}
	}
}
