package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import net.minecraft.client.CloudStatus;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * Clouds' shadows on the terrain, as Vibrant Visuals casts them. Java's cloud renderer keeps its cloud field
 * on the CPU (one cell per pixel of clouds.png, 12 blocks wide, 4 tall); this uploads which cells are filled
 * as a small texture and tracks where the field has drifted to, and the lighting pass projects each point
 * along the light onto the cloud layer to see whether a cell covers it.
 *
 * <p>Bedrock's cloud shadow strength is its {@code EmissiveMultiplierAndDesaturationAndCloudPCFAndContribution.w},
 * 0.5 in the material's defaults (the engine may set it at runtime).
 */
public final class CloudShadows {
	/** The share of direct light a cloud blocks (Bedrock's material default). */
	static final float CONTRIBUTION = 0.5f;
	private static final float CELL_BLOCKS = 12f;
	private static final float CLOUD_THICKNESS = 4f;

	private static long[] uploadedCells;
	private static GpuTexture texture;
	private static GpuTextureView view;
	private static int width = 1;
	private static int height = 1;
	private static boolean visible;
	private static float baseHeight;
	private static double fieldX;
	private static double fieldZ;

	private CloudShadows() {
	}

	/**
	 * Called with the arguments of each CloudRenderer.prepare: the cloud field, its height and where it has
	 * drifted to, as Java's cloud renderer works it out.
	 */
	public static void prepare(long[] cells, int cellsWide, int cellsHigh, CloudStatus status, float cloudHeight,
			Vec3 camera, long gameTime, float partialTick) {
		visible = status != CloudStatus.OFF && cells != null;
		if (!visible) {
			return;
		}
		if (cells != uploadedCells) {
			upload(cells, cellsWide, cellsHigh);
		}
		baseHeight = cloudHeight;
		// CloudRenderer.prepare: the field moves 0.03 blocks a tick along x and is offset 3.96 blocks along z.
		float drift = (float) (gameTime % ((long) cellsWide * 400L)) + partialTick;
		double spanX = cellsWide * (double) CELL_BLOCKS;
		double spanZ = cellsHigh * (double) CELL_BLOCKS;
		fieldX = floorMod(camera.x + drift * 0.03f, spanX);
		fieldZ = floorMod(camera.z + 3.96, spanZ);
		cameraY = camera.y;
	}

	private static double cameraY;

	private static double floorMod(double value, double span) {
		return value - Math.floor(value / span) * span;
	}

	private static void upload(long[] cells, int cellsWide, int cellsHigh) {
		GpuDevice device = RenderSystem.getDevice();
		if (view != null) {
			view.close();
			texture.close();
		}
		texture = device.createTexture(() -> "Bedrock Visuals cloud shadows",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, cellsWide, cellsHigh, 1, 1);
		ByteBuffer data = MemoryUtil.memAlloc(cellsWide * cellsHigh * 4);
		try {
			for (int z = 0; z < cellsHigh; z++) {
				for (int x = 0; x < cellsWide; x++) {
					byte filled = cells[x + z * cellsWide] != 0L ? (byte) 255 : 0;
					data.put(filled).put(filled).put(filled).put(filled);
				}
			}
			data.flip();
			device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, cellsWide, cellsHigh);
		} finally {
			MemoryUtil.memFree(data);
		}
		view = device.createTextureView(texture);
		width = cellsWide;
		height = cellsHigh;
		uploadedCells = cells;
	}

	/** The cloud cells as a texture, or null before Java's clouds have loaded. */
	static GpuTextureView view() {
		return view;
	}

	/**
	 * Two vec4s for LightingData: the camera's position in the cloud field (x, z, in blocks), the middle of the
	 * cloud layer relative to the camera, and the shadow strength (0 when off); then the field's size in
	 * cells and the cell size.
	 */
	static void write(Std140Builder builder, boolean enabled) {
		boolean on = enabled && visible && view != null;
		builder.putVec4((float) fieldX, (float) fieldZ, (float) (baseHeight + CLOUD_THICKNESS / 2 - cameraY),
				on ? CONTRIBUTION : 0f);
		builder.putVec4(width, height, CELL_BLOCKS, 0);
	}

	public static String status() {
		return !visible ? "off (clouds off)" : view == null ? "waiting for clouds" : width + "x" + height + " cells, strength " + CONTRIBUTION;
	}
}
