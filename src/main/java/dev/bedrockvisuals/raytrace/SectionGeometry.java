package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.SectionPos;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

/**
 * The terrain's bottom-level acceleration structures: one per Sodium section, built when Sodium uploads the
 * section's mesh and kept until the section changes or unloads (RTX Remix's lesson: static terrain built once,
 * never merged and rebuilt every frame).
 *
 * <p>Each section's structure holds up to three geometries, one per Sodium terrain pass: 0 solid (opaque), 1
 * cutout and 2 translucent (both non-opaque, so a ray query sees them as candidates to test). Their triangles are
 * Sodium's quads split along the 0-2 diagonal, in Sodium's order: triangle {@code p} of a geometry is quad
 * {@code p / 2}, vertices (0, 1, 2) for an even {@code p} and (2, 3, 0) for an odd one.
 *
 * <p>Sodium's vertices themselves are kept on the GPU, all passes one after another (each geometry's first vertex
 * in {@link Built#firstVertex}, and its pass in two bits a geometry of {@link Built#passes}), for shaders to read the hit's texture coordinates, colour and light from:
 * {@code CompactChunkVertex}, 20 bytes (SodiumAdapter describes them).
 */
public final class SectionGeometry {
	static final int PASSES = 3;
	static final int VERTEX_STRIDE = 20;
	/** How many sections' structures are built at most in a frame, and how many vertices. */
	private static final int MAX_SECTIONS_PER_FRAME = 96;
	private static final int MAX_VERTICES_PER_FRAME = 1 << 20;

	/** A section's mesh waiting to be built, copied out of Sodium's buffers (freed when built or replaced). */
	private record Pending(int originX, int originY, int originZ, ByteBuffer[] passes) {
		void free() {
			for (ByteBuffer pass : passes) {
				if (pass != null) {
					MemoryUtil.memFree(pass);
				}
			}
		}

		int vertices() {
			int count = 0;
			for (ByteBuffer pass : passes) {
				count += pass == null ? 0 : pass.remaining() / VERTEX_STRIDE;
			}
			return count;
		}
	}

	/** A section's built structure and its vertices. */
	record Built(int originX, int originY, int originZ, long structure, long structureAddress, RtBuffer storage,
			RtBuffer vertices, int[] firstVertex, int passes, int triangles, float[] lights, long shape) {
		/** How many emissive quads {@link #lights} holds (EmissiveLights.FLOATS each). */
		int lightCount() {
			return lights.length / EmissiveLights.FLOATS;
		}
	}

	private static final Long2ObjectLinkedOpenHashMap<Pending> PENDING = new Long2ObjectLinkedOpenHashMap<>();
	private static final Long2ObjectOpenHashMap<Built> BUILT = new Long2ObjectOpenHashMap<>();
	private static long builtBytes;
	private static long totalBuilds;

	private SectionGeometry() {
	}

	/**
	 * Sodium has a new mesh for the section (on the render thread, before it uploads it): {@code passes} holds the
	 * vertices of the solid, cutout and translucent passes (null where the section has none), which are copied.
	 */
	public static void onSectionBuilt(int chunkX, int chunkY, int chunkZ, ByteBuffer[] passes) {
		if (!RtScene.enabled()) {
			return;
		}
		long key = SectionPos.asLong(chunkX, chunkY, chunkZ);
		ByteBuffer[] copies = new ByteBuffer[PASSES];
		boolean any = false;
		for (int i = 0; i < PASSES; i++) {
			ByteBuffer pass = passes[i];
			if (pass != null && pass.remaining() >= 4 * VERTEX_STRIDE) {
				ByteBuffer copy = MemoryUtil.memAlloc(pass.remaining() / (4 * VERTEX_STRIDE) * 4 * VERTEX_STRIDE);
				MemoryUtil.memCopy(MemoryUtil.memAddress(pass), MemoryUtil.memAddress(copy), copy.remaining());
				copies[i] = copy;
				any = true;
			}
		}
		Pending old = PENDING.remove(key);
		if (old != null) {
			old.free();
		}
		if (!any) {
			remove(key);
			return;
		}
		PENDING.putAndMoveToLast(key, new Pending(chunkX << 4, chunkY << 4, chunkZ << 4, copies));
	}

	/** The section unloaded. */
	public static void onSectionRemoved(int chunkX, int chunkY, int chunkZ) {
		long key = SectionPos.asLong(chunkX, chunkY, chunkZ);
		Pending old = PENDING.remove(key);
		if (old != null) {
			old.free();
		}
		remove(key);
	}

	private static void remove(long key) {
		Built built = BUILT.remove(key);
		if (built != null) {
			// Emptied by a block broken: the cached light around it follows. Unloaded: nothing near it is in view.
			if (dev.bedrockvisuals.render.ChunkChanges.editedNear(built.originX() >> 4, built.originZ() >> 4)) {
				RtEmissive.changed(built.originX(), built.originY(), built.originZ());
			}
			free(built);
		}
	}

	private static void free(Built built) {
		RtDevice rt = RtDevice.get();
		rt.freeStructure(built.structure());
		rt.free(built.storage());
		rt.free(built.vertices());
		builtBytes -= built.storage().size() + built.vertices().size();
		EmissiveLights.changed();
	}

	/** Everything goes (the world closed, Sodium's renderer was destroyed, or the scene was turned off). */
	public static void clear() {
		PENDING.values().forEach(Pending::free);
		PENDING.clear();
		if (RtDevice.get() != null) {
			BUILT.values().forEach(SectionGeometry::free);
		}
		BUILT.clear();
		builtBytes = 0;
		EmissiveLights.clear();
		TexelCache.clear();
	}

	static Iterable<Built> built() {
		return BUILT.values();
	}

	static int builtCount() {
		return BUILT.size();
	}

	static int pendingCount() {
		return PENDING.size();
	}

	static String status() {
		return String.format(java.util.Locale.ROOT, "%d sections (%d MB, %d builds), %d waiting", BUILT.size(),
				builtBytes >> 20, totalBuilds, PENDING.size());
	}

	/**
	 * Records the builds of the oldest waiting sections, within this frame's budget, into {@code commands}. Their
	 * staging and scratch memory is freed once the submission is done. Returns whether anything was built.
	 */
	static boolean recordBuilds(RtDevice rt, VkCommandBuffer commands, List<Runnable> afterRecord) {
		List<Long> keys = new ArrayList<>();
		List<Pending> batch = new ArrayList<>();
		int vertices = 0;
		for (Iterator<Map.Entry<Long, Pending>> it = PENDING.entrySet().iterator(); it.hasNext()
				&& batch.size() < MAX_SECTIONS_PER_FRAME && vertices < MAX_VERTICES_PER_FRAME;) {
			Map.Entry<Long, Pending> entry = it.next();
			keys.add(entry.getKey());
			batch.add(entry.getValue());
			vertices += entry.getValue().vertices();
			it.remove();
		}
		if (batch.isEmpty()) {
			return false;
		}

		// Staging: every section's Sodium vertices (copied to its own buffer on the GPU), then its triangles'
		// positions as floats (read from here by the build, then dropped).
		long rawBytes = (long) vertices * VERTEX_STRIDE;
		long positionBytes = (long) vertices / 4 * 6 * 12;
		RtBuffer staging = rt.hostBuffer(rawBytes + positionBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT
				| VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
		rt.free(staging);
		ByteBuffer stage = staging.bytes();
		long rawAt = 0;
		long positionsAt = rawBytes;

		int n = batch.size();
		VkAccelerationStructureBuildGeometryInfoKHR.Buffer infos = VkAccelerationStructureBuildGeometryInfoKHR.calloc(n);
		PointerBuffer ranges = MemoryUtil.memAllocPointer(n);
		List<Object> heap = new ArrayList<>();
		afterRecord.add(() -> {
			infos.free();
			MemoryUtil.memFree(ranges);
			for (Object o : heap) {
				if (o instanceof VkAccelerationStructureGeometryKHR.Buffer g) {
					g.free();
				} else if (o instanceof VkAccelerationStructureBuildRangeInfoKHR.Buffer r) {
					r.free();
				}
			}
		});
		long[] scratchSizes = new long[n];
		Built[] results = new Built[n];
		VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1);
		try {
			for (int s = 0; s < n; s++) {
				Pending pending = batch.get(s);
				int geometries = 0;
				for (ByteBuffer pass : pending.passes()) {
					geometries += pass == null ? 0 : 1;
				}
				VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(geometries);
				VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(geometries);
				heap.add(geometry);
				heap.add(range);
				int[] counts = new int[geometries];
				int[] firstVertex = new int[PASSES];
				int passes = 0;
				float[] lights = EmissiveLights.NONE;
				long shape = 0;
				EmissiveLights.Emitters emitters = EmissiveLights.emitters(pending.originX(), pending.originY(),
						pending.originZ());
				long sectionRawStart = rawAt;
				int vertex = 0;
				int g = 0;
				int triangles = 0;
				for (int pass = 0; pass < PASSES; pass++) {
					ByteBuffer data = pending.passes()[pass];
					if (data == null) {
						continue;
					}
					firstVertex[g] = vertex;
					passes |= pass << (2 * g);
					int passVertices = data.remaining() / VERTEX_STRIDE;
					MemoryUtil.memCopy(MemoryUtil.memAddress(data), staging.mapped() + rawAt, data.remaining());
					rawAt += data.remaining();
					lights = EmissiveLights.collect(data, lights, emitters);
					shape = shape * 31 + pass + 1 ^ shapeHash(data);
					long positionsStart = positionsAt;
					positionsAt = writePositions(stage, (int) positionsAt, data);
					int passTriangles = passVertices / 4 * 2;
					geometry.get(g).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
							.flags(pass == 0 ? VK_GEOMETRY_OPAQUE_BIT_KHR : 0);
					geometry.get(g).geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
							.vertexStride(12).maxVertex(passTriangles * 3 - 1).indexType(VK_INDEX_TYPE_NONE_KHR);
					geometry.get(g).geometry().triangles().vertexData().deviceAddress(staging.address() + positionsStart);
					range.get(g).primitiveCount(passTriangles);
					counts[g] = passTriangles;
					triangles += passTriangles;
					vertex += passVertices;
					g++;
				}
				pending.free();

				VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(s).sType$Default()
						.type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
						.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
						.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR).geometryCount(geometries).pGeometries(geometry);
				VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc().sType$Default();
				vkGetAccelerationStructureBuildSizesKHR(rt.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info,
						counts, sizes);
				RtBuffer storage = rt.deviceBuffer(sizes.accelerationStructureSize(),
						VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR);
				scratchSizes[s] = sizes.buildScratchSize();
				sizes.free();
				long structure = rt.createStructure(storage, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
				info.dstAccelerationStructure(structure);
				ranges.put(s, range.address());

				long sectionRaw = rawAt - sectionRawStart;
				RtBuffer raw = rt.deviceBuffer(sectionRaw, VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
				copy.get(0).srcOffset(sectionRawStart).dstOffset(0).size(sectionRaw);
				vkCmdCopyBuffer(commands, staging.buffer(), raw.buffer(), copy);
				results[s] = new Built(pending.originX(), pending.originY(), pending.originZ(), structure,
						rt.structureAddress(structure), storage, raw, firstVertex, passes, triangles, lights, shape);
			}
		} finally {
			copy.free();
		}

		long scratchTotal = 0;
		long[] scratchOffsets = new long[n];
		for (int s = 0; s < n; s++) {
			scratchOffsets[s] = scratchTotal;
			scratchTotal += align(scratchSizes[s], rt.scratchAlignment);
		}
		RtBuffer scratch = rt.scratchBuffer(scratchTotal);
		rt.free(scratch);
		for (int s = 0; s < n; s++) {
			infos.get(s).scratchData().deviceAddress(scratch.address() + scratchOffsets[s]);
		}
		vkCmdBuildAccelerationStructuresKHR(commands, infos, ranges);

		for (int s = 0; s < n; s++) {
			Built old = BUILT.put((long) keys.get(s), results[s]);
			if (old != null) {
				free(old);
				// A section built again with other geometry (a block placed or broken): the cached light follows.
				// Rebuilt with only its light levels changed (Minecraft's light engine spreading a change over a few
				// ticks), it changes nothing the rays see: wiping the cached light again for each of those kept
				// restarting the bounce light for a second or two after a light was removed.
				//
				// Only after a block edit near it: a section is also rebuilt as the chunks around it load (its border
				// faces culled), and wiping for those, all around, every frame while arriving somewhere, kept the
				// light there at its first few noisy samples (mostly dark texels) for seconds. The averages' own
				// change test (TexelCache.blendTexel) follows what loading chunks change.
				if (old.shape() != results[s].shape()
						&& dev.bedrockvisuals.render.ChunkChanges.editedNear(old.originX() >> 4, old.originZ() >> 4)) {
					RtEmissive.changed(old.originX(), old.originY(), old.originZ());
				}
			}
			builtBytes += results[s].storage().size() + results[s].vertices().size();
			if (results[s].lightCount() > 0 || old != null && old.lightCount() > 0) {
				EmissiveLights.changed();
			}
		}
		totalBuilds += n;
		return true;
	}

	/** Writes the quads' positions (relative to the section's origin) as two triangles each; returns the end. */
	private static long writePositions(ByteBuffer stage, int at, ByteBuffer data) {
		int vertices = data.remaining() / VERTEX_STRIDE;
		int base = data.position();
		float[] x = new float[4];
		float[] y = new float[4];
		float[] z = new float[4];
		for (int quad = 0; quad < vertices / 4; quad++) {
			for (int v = 0; v < 4; v++) {
				int offset = base + (quad * 4 + v) * VERTEX_STRIDE;
				int hi = data.getInt(offset);
				int lo = data.getInt(offset + 4);
				x[v] = position(hi, lo, 0);
				y[v] = position(hi, lo, 10);
				z[v] = position(hi, lo, 20);
			}
			for (int v : QUAD_TRIANGLES) {
				stage.putFloat(at, x[v]).putFloat(at + 4, y[v]).putFloat(at + 8, z[v]);
				at += 12;
			}
		}
		return at;
	}

	/**
	 * A hash of a pass's vertices without their light levels (the low half of each vertex's last word): what the
	 * rays see of them, positions, texture coordinates, colours and materials.
	 */
	private static long shapeHash(ByteBuffer data) {
		long hash = 1125899906842597L;
		int words = data.remaining() / 4;
		int base = data.position();
		for (int i = 0; i < words; i++) {
			int word = data.getInt(base + i * 4);
			if (i % 5 == 4) {
				word &= 0xFFFF0000;
			}
			hash = (hash ^ word) * 0x100000001b3L;
		}
		return hash;
	}

	/** The two triangles of a quad: (0, 1, 2) and (2, 3, 0). */
	private static final int[] QUAD_TRIANGLES = {0, 1, 2, 2, 3, 0};

	/**
	 * One axis of a CompactChunkVertex position: 20 bits, the high 10 in the first word and the low 10 in the
	 * second, mapping [0, 2^20) to [-8, 24) blocks from the section's origin.
	 */
	static float position(int hi, int lo, int shift) {
		int value = ((hi >>> shift) & 0x3FF) << 10 | ((lo >>> shift) & 0x3FF);
		return value * (32.0f / 1048576.0f) - 8.0f;
	}

	private static long align(long value, long alignment) {
		return (value + alignment - 1) / alignment * alignment;
	}

	static void forEachBuilt(Consumer<Built> action) {
		BUILT.values().forEach(action);
	}
}
