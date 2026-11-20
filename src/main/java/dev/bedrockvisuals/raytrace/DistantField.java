package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import dev.bedrockvisuals.render.DistantHeights;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Distant Horizons' terrain past the loaded chunks, in the ray-traced scene: the far terrain's heights
 * (render/DistantHeights, the top of the highest solid block every 8 blocks) as columns, a top and the walls
 * down to lower neighbours, within {@link #RADIUS} cells of the camera, leaving out the loaded chunks (Sodium's
 * own geometry is there). So far hills and mountains shade the near terrain from a low sun (ray-traced shadows),
 * and bounce rays meet them.
 *
 * <p>An instance of kind 2 under the terrain mask, its vertices in the entities' layout (EntityGeometry: 36 bytes,
 * position then colour, quads), so the shaders take it as they take entities: opaque, its vertex colour (the top
 * block's, linear) the bounce's albedo. Rebuilt when the camera has moved {@link #MOVE_CELLS} cells, the height
 * map changed world, or every {@link #REFRESH_NANOS} while it fills in; kept between.
 */
final class DistantField {
	static final int RADIUS = 128;
	private static final int MOVE_CELLS = 16;
	private static final long REFRESH_NANOS = 30_000_000_000L;
	/** The columns are made on a worker (40 to 90 ms of CPU for 167 000 quads); the structure is built from them. */
	private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors
			.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "Bedrock Visuals far terrain");
				thread.setDaemon(true);
				return thread;
			});
	private static java.util.concurrent.Future<Columns> pending;

	/** A worker's columns: positions for the structure, vertices for the shaders, relative to the origin. */
	private record Columns(ByteBuffer positions, ByteBuffer raw, int count, int originX, int originY, int originZ) {
		void free() {
			MemoryUtil.memFree(positions);
			MemoryUtil.memFree(raw);
		}
	}
	private static final int CELL = 8;
	private static final int STRIDE = EntityGeometry.VERTEX_STRIDE;

	private static long address;
	private static RtBuffer vertices;
	private static List<Runnable> owned = new ArrayList<>();
	private static int originX;
	private static int originY;
	private static int originZ;
	private static int builtCellX = Integer.MIN_VALUE;
	private static int builtCellZ;
	private static int builtEpoch = -1;
	private static long builtAt;
	private static int quads;
	private static double buildMillis;

	private DistantField() {
	}

	/** Inside RtScene's submission: rebuilds the structure when due. Whether there is one this frame. */
	static boolean record(RtDevice rt, VkCommandBuffer commands, MemoryStack stack, double cameraX, double cameraZ,
			int minY) {
		int epoch = DistantHeights.epochForScene();
		if (epoch % 2 == 0) {
			drop();
			builtEpoch = epoch;
			return false;
		}
		int cellX = Math.floorDiv((int) Math.floor(cameraX), CELL);
		int cellZ = Math.floorDiv((int) Math.floor(cameraZ), CELL);
		long now = System.nanoTime();
		boolean due = epoch != builtEpoch || Math.abs(cellX - builtCellX) >= MOVE_CELLS
				|| Math.abs(cellZ - builtCellZ) >= MOVE_CELLS || now - builtAt > REFRESH_NANOS;
		if (due && pending == null) {
			int loaded = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16 / CELL;
			pending = WORKER.submit(() -> columns(cellX, cellZ, minY, loaded));
			builtEpoch = epoch;
			builtCellX = cellX;
			builtCellZ = cellZ;
			builtAt = now;
		}
		if (pending != null && pending.isDone()) {
			try {
				Columns columns = pending.get();
				long start = System.nanoTime();
				try {
					upload(rt, commands, stack, columns);
				} finally {
					columns.free();
				}
				buildMillis = (System.nanoTime() - start) / 1e6;
				dev.bedrockvisuals.BedrockVisualsClient.LOGGER.info("[raytrace] {}", status());
			} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
				dev.bedrockvisuals.BedrockVisualsClient.LOGGER.warn("[raytrace] far terrain failed", e);
			}
			pending = null;
		}
		return address != 0;
	}

	private static void drop() {
		owned.forEach(Runnable::run);
		owned = new ArrayList<>();
		address = 0;
		vertices = null;
		quads = 0;
	}

	/** On the worker: the columns around the camera's cell, leaving out the loaded chunks (loaded: in cells). */
	private static Columns columns(int cellX, int cellZ, int minY, int loaded) {
		int ox = cellX * CELL;
		int oz = cellZ * CELL;
		int oy = minY;
		ByteBuffer positions = MemoryUtil.memAlloc(1 << 20);
		ByteBuffer raw = MemoryUtil.memAlloc(1 << 20);
		int count = 0;
		try {
			for (int dz = -RADIUS; dz < RADIUS; dz++) {
				for (int dx = -RADIUS; dx < RADIUS; dx++) {
					if (Math.abs(dx) <= loaded && Math.abs(dz) <= loaded) {
						continue;
					}
					int wx = (cellX + dx) * CELL;
					int wz = (cellZ + dz) * CELL;
					int top = DistantHeights.topAt(wx, wz);
					if (top == Integer.MIN_VALUE) {
						continue;
					}
					int colour = linearColour(DistantHeights.colourAt(wx, wz));
					float x0 = wx - ox;
					float x1 = x0 + CELL;
					float z0 = wz - oz;
					float z1 = z0 + CELL;
					float y = top - oy;
					positions = ensure(positions, 6 * 12 * 5);
					raw = ensure(raw, 4 * STRIDE * 5);
					quad(positions, raw, colour, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1);
					count++;
					// Walls down to lower neighbours (none towards a column without a height yet).
					int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
					for (int[] side : sides) {
						int neighbour = DistantHeights.topAt(wx + side[0] * CELL, wz + side[1] * CELL);
						if (neighbour == Integer.MIN_VALUE || neighbour >= top) {
							continue;
						}
						float low = neighbour - oy;
						if (side[0] == 1) {
							quad(positions, raw, colour, x1, low, z0, x1, low, z1, x1, y, z1, x1, y, z0);
						} else if (side[0] == -1) {
							quad(positions, raw, colour, x0, low, z1, x0, low, z0, x0, y, z0, x0, y, z1);
						} else if (side[1] == 1) {
							quad(positions, raw, colour, x1, low, z1, x0, low, z1, x0, y, z1, x1, y, z1);
						} else {
							quad(positions, raw, colour, x0, low, z0, x1, low, z0, x1, y, z0, x0, y, z0);
						}
						count++;
					}
				}
			}
			positions.flip();
			raw.flip();
			return new Columns(positions, raw, count, ox, oy, oz);
		} catch (RuntimeException e) {
			MemoryUtil.memFree(positions);
			MemoryUtil.memFree(raw);
			throw e;
		}
	}

	/** On the render thread, in the scene's submission: the structure from a worker's columns. */
	private static void upload(RtDevice rt, VkCommandBuffer commands, MemoryStack stack, Columns columns) {
		drop();
		int count = columns.count();
		if (count == 0) {
			return;
		}
		ByteBuffer positions = columns.positions();
		ByteBuffer raw = columns.raw();
		{
			RtBuffer input = rt.hostBuffer(positions.remaining(),
					VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
			rt.free(input);
			input.bytes().put(positions);
			RtBuffer kept = rt.hostBuffer(raw.remaining(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
			kept.bytes().put(raw);
			owned.add(() -> rt.free(kept));
			VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
			geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
					.flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
			geometry.get(0).geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
					.vertexStride(12).maxVertex(count * 6 - 1).indexType(VK_INDEX_TYPE_NONE_KHR);
			geometry.get(0).geometry().triangles().vertexData().deviceAddress(input.address());
			long blas = RtScene.buildStructure(rt, commands, stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
					VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR, geometry, count * 2, owned);
			address = rt.structureAddress(blas);
			vertices = kept;
			quads = count;
			originX = columns.originX();
			originY = columns.originY();
			originZ = columns.originZ();
		}
	}

	/** A quad as two triangles (0, 1, 2) and (2, 3, 0) for the structure, and its four vertices for the shaders. */
	private static void quad(ByteBuffer positions, ByteBuffer raw, int colour, float... v) {
		int[] order = {0, 1, 2, 2, 3, 0};
		for (int k : order) {
			positions.putFloat(v[k * 3]).putFloat(v[k * 3 + 1]).putFloat(v[k * 3 + 2]);
		}
		for (int k = 0; k < 4; k++) {
			int at = raw.position();
			raw.putFloat(v[k * 3]).putFloat(v[k * 3 + 1]).putFloat(v[k * 3 + 2]);
			raw.putInt(colour);
			while (raw.position() < at + STRIDE) {
				raw.put((byte) 0);
			}
		}
	}

	/** 0xRRGGBB in sRGB (0 unknown: a middling grey) to RGBA bytes of its linear colour, as the shaders unpack. */
	private static int linearColour(int srgb) {
		if (srgb == 0) {
			return 0xFF666666;
		}
		int r = (int) Math.round(Math.pow((srgb >> 16 & 0xFF) / 255.0, 2.2) * 255);
		int g = (int) Math.round(Math.pow((srgb >> 8 & 0xFF) / 255.0, 2.2) * 255);
		int b = (int) Math.round(Math.pow((srgb & 0xFF) / 255.0, 2.2) * 255);
		return 0xFF << 24 | b << 16 | g << 8 | r; // little-endian: r first, as unpackUnorm4x8 reads
	}

	private static ByteBuffer ensure(ByteBuffer buffer, int more) {
		if (buffer.remaining() >= more) {
			return buffer;
		}
		int position = buffer.position();
		ByteBuffer grown = MemoryUtil.memRealloc(buffer, Math.max(buffer.capacity() * 2, position + more));
		grown.order(java.nio.ByteOrder.nativeOrder());
		grown.limit(grown.capacity()).position(position);
		return grown;
	}

	static long structureAddress() {
		return address;
	}

	static long verticesAddress() {
		return vertices == null ? 0 : vertices.address();
	}

	static int originX() {
		return originX;
	}

	static int originY() {
		return originY;
	}

	static int originZ() {
		return originZ;
	}

	static String status() {
		return address == 0 ? "no far terrain" : String.format(Locale.ROOT, "far terrain %d quads (built in %.1f ms)",
				quads, buildMillis);
	}
}
