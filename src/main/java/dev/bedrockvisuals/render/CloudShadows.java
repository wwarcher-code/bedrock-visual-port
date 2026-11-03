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
 * <p>Bedrock's cloud shadow strength is {@code cloud_shadow_contribution} in its shadow configuration
 * (renderer/platform_config/pc/shadow_configuration.pc.json, 0.4 at every quality), sent as
 * {@code EmissiveMultiplierAndDesaturationAndCloudPCFAndContribution.w}; the lighting shader keeps
 * {@code max(visibility, 1 - contribution)}.
 */
public final class CloudShadows {
	/** The share of direct light a cloud blocks (cloud_shadow_contribution). */
	static final float CONTRIBUTION = 0.4f;
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
			int rangeChunks, Vec3 camera, long gameTime, float partialTick) {
		meshRange = rangeChunks * 16f;
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
	/** Java's cloud range in blocks: its mesh covers ceil(range / 12) cells around the camera's cell. */
	private static float meshRange;

	/**
	 * Bedrock's cloud distance (DistanceControl.x, 1152 at 24 chunks in the captures): 3 × the render
	 * distance. With Distant Horizons (or another far renderer) the terrain reaches further, so the clouds
	 * reach at least as far as it does.
	 */
	static float farDistance() {
		float bedrock = 3f * net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16f;
		int distant = dev.bedrockvisuals.lod.DistantTerrain.source().map(s -> s.renderDistanceBlocks()).orElse(0);
		return Math.max(bedrock, distant);
	}

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
		uploadCoverage(device, cells, cellsWide, cellsHigh);
		width = cellsWide;
		height = cellsHigh;
		uploadedCells = cells;
	}

	private static GpuTexture coverage;
	private static GpuTextureView coverageView;

	/**
	 * The filled cells again, with a box-filtered mip chain: each level holds the share of cloud over 2^level
	 * cells, which the far clouds read where a pixel spans several cells, instead of one cell per pixel
	 * (which sparkles).
	 */
	private static void uploadCoverage(GpuDevice device, long[] cells, int cellsWide, int cellsHigh) {
		if (coverageView != null) {
			coverageView.close();
			coverage.close();
		}
		int levels = 1;
		while ((cellsWide >> levels) > 0 && (cellsHigh >> levels) > 0) {
			levels++;
		}
		coverage = device.createTexture(() -> "Bedrock Visuals cloud coverage",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, cellsWide, cellsHigh, 1,
				levels);
		float[] level = new float[cellsWide * cellsHigh];
		for (int i = 0; i < level.length; i++) {
			level[i] = cells[i] != 0L ? 1f : 0f;
		}
		int w = cellsWide;
		int h = cellsHigh;
		for (int mip = 0; mip < levels; mip++) {
			ByteBuffer data = MemoryUtil.memAlloc(w * h * 4);
			try {
				for (float value : level) {
					byte b = (byte) Math.round(value * 255f);
					data.put(b).put(b).put(b).put((byte) 255);
				}
				data.flip();
				device.createCommandEncoder().writeToTexture(coverage, data, mip, 0, 0, 0, w, h);
			} finally {
				MemoryUtil.memFree(data);
			}
			if (mip + 1 < levels) {
				int nw = Math.max(1, w / 2);
				int nh = Math.max(1, h / 2);
				float[] next = new float[nw * nh];
				for (int y = 0; y < nh; y++) {
					for (int x = 0; x < nw; x++) {
						next[x + y * nw] = (level[2 * x + 2 * y * w] + level[2 * x + 1 + 2 * y * w]
								+ level[2 * x + (2 * y + 1) * w] + level[2 * x + 1 + (2 * y + 1) * w]) * 0.25f;
					}
				}
				level = next;
				w = nw;
				h = nh;
			}
		}
		coverageView = device.createTextureView(coverage);
	}

	/** The filtered coverage, or a placeholder before Java's clouds have loaded. */
	static GpuTextureView coverageOrEmpty() {
		return coverageView != null ? coverageView : viewOrEmpty();
	}

	private static GpuTextureView empty;

	/**
	 * The cloud cells, or before Java's clouds have loaded a 1x1 empty texture (CloudShadowSize.w is then 0, and
	 * the shaders don't read it, but a declared sampler must be bound).
	 */
	static GpuTextureView viewOrEmpty() {
		if (view != null) {
			return view;
		}
		if (empty == null) {
			GpuDevice device = RenderSystem.getDevice();
			GpuTexture texture = device.createTexture(() -> "Bedrock Visuals no cloud cells",
					GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			ByteBuffer data = MemoryUtil.memCalloc(4);
			try {
				device.createCommandEncoder().writeToTexture(texture, data, 0, 0, 0, 0, 1, 1);
			} finally {
				MemoryUtil.memFree(data);
			}
			empty = device.createTextureView(texture);
		}
		return empty;
	}

	/** The cloud cells as a texture, or null before Java's clouds have loaded. */
	static GpuTextureView view() {
		return view;
	}

	/**
	 * Two vec4s for LightingData: the camera's position in the cloud field (x, z, in blocks), the middle of the
	 * cloud layer relative to the camera, and the shadow strength (0 when off); then the field's size in
	 * cells, the cell size, and 1 when the cell texture is there (the clouds use it whether or not shadows are
	 * on); then the far clouds' parameters.
	 */
	static void write(Std140Builder builder, boolean enabled) {
		boolean on = enabled && visible && view != null;
		builder.putVec4((float) fieldX, (float) fieldZ, (float) (baseHeight + CLOUD_THICKNESS / 2 - cameraY),
				on ? CONTRIBUTION : 0f);
		builder.putVec4(width, height, CELL_BLOCKS, visible && view != null ? 1 : 0);
		// Far clouds (the lighting pass traces the field beyond Java's mesh): Java's cloud range, the far
		// distance, and 1 when on.
		builder.putVec4(meshRange, farDistance(), 0, enabled && visible && view != null && meshRange > 0 ? 1 : 0);
	}

	/** The far clouds, for diagnostics. */
	public static String farStatus() {
		if (!visible || view == null || meshRange <= 0) {
			return "off (no Java clouds)";
		}
		float range = meshRange;
		return String.format(java.util.Locale.ROOT, "traced beyond Java's mesh (cloud range %.0f blocks) to %.0f blocks"
				+ " (Bedrock's 3 x render distance, or Distant Horizons' if further), into the sky by %.0f", range,
				farDistance(), 1.9f * farDistance());
	}

	public static String status() {
		return !visible ? "off (Java's Clouds setting is off, or Distant Horizons draws the clouds instead)" : view == null ? "waiting for clouds" : width + "x" + height + " cells, strength " + CONTRIBUTION;
	}
}
