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

		List<MaterialAtlasImage.Slot> slots = new ArrayList<>();
		TextureAtlasSprite missing = blockAtlas.missingSprite();
		for (String name : map.javaTextures()) {
			TextureAtlasSprite sprite = blockAtlas.getSprite(Identifier.withDefaultNamespace("block/" + name));
			if (sprite != missing) {
				slots.add(new MaterialAtlasImage.Slot(name, sprite.getX(), sprite.getY(), sprite.contents().width(),
						sprite.contents().height()));
			}
		}
		int width = atlasTexture.getWidth(0);
		int height = atlasTexture.getHeight(0);
		MaterialAtlasImage.Result image = MaterialAtlasImage.build(width, height, atlasTexture.getMipLevels(), slots,
				name -> map.textureSetFor(pack, name), pack.pbrFallback().blocks());

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
		status = String.format("%dx%d, %d mip levels, %d of %d block sprites from Bedrock texture sets, %d problems",
				width, height, image.levels().size(), image.filledSlots(), slots.size(), image.problems().size());
		BedrockVisualsClient.LOGGER.info("[materials] built {}x{} material atlas with {} mip levels in {} ms: "
						+ "{} of {} block sprites from Bedrock texture sets", width, height, image.levels().size(),
				(System.nanoTime() - start) / 1_000_000, image.filledSlots(), slots.size());
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
