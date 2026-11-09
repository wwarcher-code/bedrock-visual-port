package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.LocalLights;
import dev.bedrockvisuals.bedrock.Rgba;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import org.lwjgl.system.MemoryUtil;

/**
 * Bedrock's coloured block light (GPU block lighting: the lighting pass reads each section's VoxelBuffer, RGB and
 * intensity per block, at the surface plus 0.2 of its normal, blended across the face). Bedrock spreads the light
 * on the CPU and ships no shader for it; here, each time the light volume (LightVolume) has been scanned through,
 * the light of the blocks the packs give a colour (local_lighting.json, either light type) is spread from each
 * of them through the open blocks, a level fewer per step as Minecraft's is, on a worker thread. Each block of the
 * volume gets the colours reaching it, mixed by their strength, and the strongest level among them; the lighting
 * pass tints Minecraft's block light by that colour where those levels make up the light there.
 *
 * <p>The texture has the light volume's layout: RGB the mixed colour (linear, its largest channel 1), A the
 * coloured light's level (× 17).
 */
public final class BlockLightColors {
	/** A pack-coloured light block: world position, light level, and linear colour (largest channel 1). */
	record Emitter(int x, int y, int z, int level, float[] color) {
	}

	private static final int SX = LightVolume.SIZE_XZ;
	private static final int SY = LightVolume.SIZE_Y;
	private static final int TILES_X = LightVolume.TILES_X;

	private static Map<Block, float[]> declared;
	private static Object declaredFrom;
	private static GpuTexture texture;
	private static GpuTextureView view;
	private static CompletableFuture<ByteBuffer> pending;
	private static boolean any;
	private static int lastEmitters;
	private static long lastRebuild;
	/** The spread's work arrays, kept between spreads (one runs at a time). */
	private static float[] red;
	private static float[] green;
	private static float[] blue;
	private static float[] weight;
	private static byte[] strongest;
	private static int[] stamp;
	private static int[] queue;

	private BlockLightColors() {
	}

	/** The blocks the packs give a light colour, by block (looked up again when the packs change). */
	static Map<Block, float[]> declared() {
		Object pack = BedrockInputs.pack().orElse(null);
		if (declared == null || pack != declaredFrom) {
			declaredFrom = pack;
			Map<Block, float[]> blocks = new HashMap<>();
			LocalLights lights = BedrockInputs.pack().map(p -> p.localLights()).orElse(null);
			if (lights != null) {
				for (Map.Entry<String, LocalLights.Light> entry : lights.byBlock().entrySet()) {
					Identifier id = Identifier.tryParse(entry.getKey());
					if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
						blocks.put(BuiltInRegistries.BLOCK.getValue(id), linear(entry.getValue().color()));
					}
				}
			}
			declared = Map.copyOf(blocks);
		}
		return declared;
	}

	private static float[] linear(Rgba color) {
		float r = (float) Math.pow(color.r(), 2.2);
		float g = (float) Math.pow(color.g(), 2.2);
		float b = (float) Math.pow(color.b(), 2.2);
		float max = Math.max(Math.max(r, g), Math.max(b, 1e-4f));
		return new float[] {r / max, g / max, b / max};
	}

	/** After a full scan of the light volume: spreads the coloured light again, off the render thread. */
	static void rebuild(int cameraX, int cameraY, int cameraZ) {
		long now = System.currentTimeMillis();
		if (pending != null || now - lastRebuild < 1000) {
			return;
		}
		lastRebuild = now;
		List<Emitter> emitters = new ArrayList<>();
		for (int i = 0; i < SY; i++) {
			List<Emitter> slice = LightVolume.EMITTERS.get(i);
			if (slice != null) {
				emitters.addAll(slice);
			}
		}
		lastEmitters = emitters.size();
		if (emitters.isEmpty() && !any) {
			return;
		}
		byte[] solid = LightVolume.SOLID.clone();
		pending = CompletableFuture.supplyAsync(() -> spread(emitters, solid, cameraX, cameraY, cameraZ));
	}

	/** Once a frame: uploads a finished spread. */
	static void update() {
		if (pending == null || !pending.isDone()) {
			return;
		}
		ByteBuffer data;
		try {
			data = pending.join();
		} catch (RuntimeException e) {
			BedrockVisualsClient.LOGGER.error("[lighting] coloured block light failed", e);
			pending = null;
			return;
		}
		pending = null;
		try {
			create();
			RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0,
					SX * TILES_X, SX * (SY / TILES_X));
			any = lastEmitters > 0;
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	private static ByteBuffer spread(List<Emitter> emitters, byte[] solid, int cameraX, int cameraY, int cameraZ) {
		int volume = SX * SX * SY;
		if (red == null) {
			red = new float[volume];
			green = new float[volume];
			blue = new float[volume];
			weight = new float[volume];
			strongest = new byte[volume];
			stamp = new int[volume];
			// A light of level 15 reaches at most 2 × 15³ blocks or so; four ints each.
			queue = new int[4 * 16384];
		}
		java.util.Arrays.fill(red, 0f);
		java.util.Arrays.fill(green, 0f);
		java.util.Arrays.fill(blue, 0f);
		java.util.Arrays.fill(weight, 0f);
		java.util.Arrays.fill(strongest, (byte) 0);
		java.util.Arrays.fill(stamp, 0);
		int minX = cameraX - SX / 2;
		int minY = cameraY - SY / 2;
		int minZ = cameraZ - SX / 2;
		int run = 0;
		for (Emitter emitter : emitters) {
			if (emitter.x() < minX || emitter.x() >= minX + SX || emitter.y() < minY || emitter.y() >= minY + SY
					|| emitter.z() < minZ || emitter.z() >= minZ + SX) {
				continue;
			}
			run++;
			// Breadth first, a level fewer per step, through blocks that aren't solid (the light's own block
			// may be). Each queue entry: x, y, z (world), level.
			int head = 0;
			int tail = 0;
			queue[tail++] = emitter.x();
			queue[tail++] = emitter.y();
			queue[tail++] = emitter.z();
			queue[tail++] = emitter.level();
			stamp[LightVolume.index(emitter.x(), emitter.y(), emitter.z())] = run;
			while (head < tail) {
				int x = queue[head++];
				int y = queue[head++];
				int z = queue[head++];
				int level = queue[head++];
				int index = LightVolume.index(x, y, z);
				float w = (level / 15f) * (level / 15f);
				red[index] += emitter.color()[0] * w;
				green[index] += emitter.color()[1] * w;
				blue[index] += emitter.color()[2] * w;
				weight[index] += w;
				if (level > (strongest[index] & 0xFF)) {
					strongest[index] = (byte) level;
				}
				if (level <= 1) {
					continue;
				}
				for (int direction = 0; direction < 6; direction++) {
					int nx = x + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
					int ny = y + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
					int nz = z + (direction == 4 ? 1 : direction == 5 ? -1 : 0);
					if (nx < minX || nx >= minX + SX || ny < minY || ny >= minY + SY || nz < minZ || nz >= minZ + SX) {
						continue;
					}
					int next = LightVolume.index(nx, ny, nz);
					if (stamp[next] == run || solid[next] != 0 || tail + 4 > queue.length) {
						continue;
					}
					stamp[next] = run;
					queue[tail++] = nx;
					queue[tail++] = ny;
					queue[tail++] = nz;
					queue[tail++] = level - 1;
				}
			}
		}
		int width = SX * TILES_X;
		ByteBuffer data = MemoryUtil.memAlloc(width * SX * (SY / TILES_X) * 4);
		for (int i = 0; i < data.capacity(); i += 4) {
			int texelX = (i / 4) % width;
			int texelY = (i / 4) / width;
			int y = (texelY / SX) * TILES_X + texelX / SX;
			int index = (texelX % SX) + (texelY % SX) * SX + y * SX * SX;
			float w = weight[index];
			if (w <= 0f) {
				data.putInt(i, 0);
				continue;
			}
			float r = red[index] / w;
			float g = green[index] / w;
			float b = blue[index] / w;
			float max = Math.max(Math.max(r, g), Math.max(b, 1e-4f));
			data.put(i, (byte) Math.round(r / max * 255));
			data.put(i + 1, (byte) Math.round(g / max * 255));
			data.put(i + 2, (byte) Math.round(b / max * 255));
			data.put(i + 3, (byte) ((strongest[index] & 0xFF) * 17));
		}
		return data;
	}

	private static void create() {
		if (texture != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		texture = device.createTexture(() -> "Bedrock Visuals block light colours",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM,
				SX * TILES_X, SX * (SY / TILES_X), 1, 1);
		view = device.createTextureView(texture);
	}

	/** The colours, or null before the first spread (the lighting pass then binds a stand-in and ignores it). */
	static GpuTextureView view() {
		return any ? view : null;
	}

	public static String status() {
		int declaredCount = declared == null ? 0 : declared.size();
		if (declaredCount == 0) {
			return "none declared by the packs";
		}
		return (any ? "on, " : "none near, ") + lastEmitters + " coloured light blocks of " + declaredCount
				+ " declared types";
	}
}
