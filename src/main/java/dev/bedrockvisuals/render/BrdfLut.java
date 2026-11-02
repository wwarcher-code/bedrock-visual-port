package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.bedrock.BedrockInstall;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

/**
 * Bedrock's split-sum BRDF table, {@code renderer/brdf_lut.png} from the install (256×256). The indirect
 * specular pass (DeferredIndirectSpecular) reads it at (saturate(N·V), 1 - roughness) and scales the reflected
 * light by {@code F0 × r + g}. Without an install the lighting pass falls back to Schlick's approximation.
 */
public final class BrdfLut {
	private static GpuTextureView view;
	private static boolean loaded;

	private BrdfLut() {
	}

	/** The table, or null without a Bedrock install. */
	static GpuTextureView view() {
		if (!loaded) {
			loaded = true;
			load();
		}
		return view;
	}

	private static void load() {
		Path file = BedrockInstall.dataFolder().map(data -> data.resolve("renderer/brdf_lut.png")).orElse(null);
		if (file == null || !Files.isRegularFile(file)) {
			BedrockVisualsClient.LOGGER.warn("[lighting] no renderer/brdf_lut.png in the Bedrock install; approximating it");
			return;
		}
		try {
			BufferedImage image = ImageIO.read(file.toFile());
			int width = image.getWidth();
			int height = image.getHeight();
			GpuDevice device = RenderSystem.getDevice();
			GpuTexture texture = device.createTexture(() -> "Bedrock Visuals BRDF table",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
			ByteBuffer data = MemoryUtil.memAlloc(width * height * 4);
			try {
				// Rows top first, as the engine samples it: v 0 is the top row.
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						int argb = image.getRGB(x, y);
						data.put((byte) (argb >> 16)).put((byte) (argb >> 8)).put((byte) argb).put((byte) 255);
					}
				}
				data.flip();
				device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, width, height);
			} finally {
				MemoryUtil.memFree(data);
			}
			view = device.createTextureView(texture);
			BedrockVisualsClient.LOGGER.info("[lighting] BRDF table {}x{} from the Bedrock install", width, height);
		} catch (IOException | RuntimeException e) {
			BedrockVisualsClient.LOGGER.warn("[lighting] could not load {}", file, e);
		}
	}
}
