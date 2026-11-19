package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK12.*;

import dev.bedrockvisuals.BedrockVisualsClient;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/**
 * The world as ray queries see it, rebuilt after every render of the level: a top-level acceleration structure
 * over every built terrain section (SectionGeometry) and the frame's entities (EntityGeometry).
 *
 * <p>Positions are relative to {@link Frame#origin}, the block the camera is in, so they stay small: a section's
 * instance is moved by its origin minus that block, the entities' (posed relative to the camera) by the camera
 * minus it.
 *
 * <p>Each instance's custom index points into {@link Frame#instances}, a table of 32 bytes an instance (std430):
 * the address of its vertices (two uints), its kind (0 terrain, 1 entities), its vertex stride, the first
 * vertex of each of its (up to three) geometries, and each geometry's terrain pass (two bits each: 0 solid, 1
 * cutout, 2 translucent). Instance masks: {@link #MASK_TERRAIN}, {@link #MASK_ENTITIES}.
 *
 * <p>Off unless something asks for it ({@link #setEnabled}): it costs memory for every section and a build every
 * frame.
 */
public final class RtScene {
	public static final int MASK_TERRAIN = 0x01;
	public static final int MASK_ENTITIES = 0x02;
	static final int INSTANCE_INFO_SIZE = 32;
	/** Entities this close to the camera are kept even out of view, for shadows and reflections (blocks). */
	public static final double OFF_SCREEN_ENTITY_RANGE = 48;

	private static boolean enabled;
	private static boolean failed;
	private static String failure = "";
	private static Frame current;
	private static int lastEntityQuads;
	private static Vec3 cameraPosition = Vec3.ZERO;
	private static CameraRenderState levelCamera;
	private static boolean builtThisLevel;
	/** The CPU time of building the scene, averaged over recent frames (ms). */
	private static double buildMillis;

	/** One frame's scene, ready on the GPU once the submission with {@link #serial} has run. */
	record Frame(long tlas, long serial, int originX, int originY, int originZ, double cameraX, double cameraY,
			double cameraZ, RtBuffer instances, int instanceCount, CameraRenderState camera,
			List<Runnable> release) {
	}

	private RtScene() {
	}

	public static boolean enabled() {
		return enabled && !failed;
	}

	/**
	 * Turns the scene on or off. Turning it on rebuilds every section (Sodium hands their meshes over as it
	 * uploads them); turning it off frees everything.
	 */
	public static void setEnabled(boolean on) {
		if (on == enabled) {
			return;
		}
		if (on && !RayQuery.available()) {
			BedrockVisualsClient.LOGGER.warn("[raytrace] no ray queries on this device: the scene stays off");
			return;
		}
		enabled = on;
		failed = false;
		if (!on) {
			release();
			SectionGeometry.clear();
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (on && minecraft.level != null) {
			minecraft.levelExtractor.allChanged();
		}
	}

	/** The world went away (Sodium destroyed its renderer). */
	public static void onWorldClosed() {
		release();
		SectionGeometry.clear();
	}

	/** The camera, as of the last frame (for keeping entities out of view). */
	public static Vec3 cameraPosition() {
		return cameraPosition;
	}

	/** Whether an entity out of view is still drawn, for the scene's sake. */
	public static boolean keepsOffScreen(double x, double y, double z) {
		return enabled() && cameraPosition.distanceToSqr(x, y, z) < OFF_SCREEN_ENTITY_RANGE * OFF_SCREEN_ENTITY_RANGE;
	}

	static Frame current() {
		return current;
	}

	private static void release() {
		if (current != null && RtDevice.get() != null) {
			current.release().forEach(Runnable::run);
		}
		current = null;
	}

	/** The level starts rendering (its entities are captured from here on). */
	public static void beginLevel(CameraRenderState camera) {
		levelCamera = camera;
		builtThisLevel = false;
		EntityGeometry.beginLevel();
	}

	/**
	 * Builds this render of the level's scene, once, if it is on: as soon as Minecraft has prepared the level's
	 * entities (before the deferred lighting, which traces through it), or after the level at the latest.
	 * Returns the scene, or null.
	 */
	public static Frame ensureBuilt() {
		if (!enabled() || levelCamera == null) {
			return null;
		}
		if (builtThisLevel) {
			return current;
		}
		builtThisLevel = true;
		try {
			long start = System.nanoTime();
			build(levelCamera);
			buildMillis += ((System.nanoTime() - start) / 1e6 - buildMillis) * 0.05;
			return current;
		} catch (RuntimeException e) {
			failed = true;
			failure = e.getMessage();
			BedrockVisualsClient.LOGGER.error("[raytrace] building the scene failed; it is off now", e);
			release();
			SectionGeometry.clear();
			return null;
		}
	}

	/** The level has been drawn (before the hand). */
	public static void afterLevel() {
		EntityGeometry.endLevel();
		Frame frame = ensureBuilt();
		if (frame != null) {
			RtDebugView.afterScene(frame);
		}
	}

	private static void build(CameraRenderState camera) {
		RtDevice rt = RtDevice.get();
		rt.poll();
		Vec3 cam = camera.pos;
		cameraPosition = cam;
		int ox = (int) Math.floor(cam.x);
		int oy = (int) Math.floor(cam.y);
		int oz = (int) Math.floor(cam.z);

		List<Runnable> afterRecord = new ArrayList<>();
		List<Runnable> release = new ArrayList<>();
		long[] tlasHandle = new long[1];
		RtBuffer[] instanceInfo = new RtBuffer[1];
		int[] instanceCount = new int[1];

		long serial = rt.submit(commands -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				SectionGeometry.recordBuilds(rt, commands, afterRecord);

				// The entities: one structure, built for this frame only.
				int quads = EntityGeometry.quads();
				lastEntityQuads = quads;
				long entityAddress = 0;
				RtBuffer entityVertices = null;
				if (quads > 0) {
					ByteBuffer positions = EntityGeometry.trianglePositions();
					RtBuffer input = rt.hostBuffer(positions.remaining(),
							VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
					rt.free(input);
					input.bytes().put(positions);
					ByteBuffer raw = EntityGeometry.rawVertices();
					entityVertices = rt.hostBuffer(raw.remaining(), VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
					entityVertices.bytes().put(raw);
					RtBuffer kept = entityVertices;
					release.add(() -> rt.free(kept));

					VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
					geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
							.flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
					geometry.get(0).geometry().triangles().sType$Default().vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
							.vertexStride(12).maxVertex(quads * 6 - 1).indexType(VK_INDEX_TYPE_NONE_KHR);
					geometry.get(0).geometry().triangles().vertexData().deviceAddress(input.address());
					long blas = buildStructure(rt, commands, stack, VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR,
							VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR, geometry, quads * 2, release);
					entityAddress = rt.structureAddress(blas);
				}

				// The bottom levels (and the copies of the sections' vertices) are done before the top level is built.
				barrier(commands, stack, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR | VK_ACCESS_TRANSFER_WRITE_BIT,
						VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR,
						VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR | VK_PIPELINE_STAGE_TRANSFER_BIT,
						VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR);

				int count = SectionGeometry.builtCount() + (entityAddress != 0 ? 1 : 0);
				RtBuffer instances = rt.hostBuffer((long) Math.max(count, 1) * 64,
						VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR);
				rt.free(instances);
				RtBuffer info = rt.hostBuffer((long) Math.max(count, 1) * INSTANCE_INFO_SIZE,
						VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
				release.add(() -> rt.free(info));
				ByteBuffer out = instances.bytes();
				ByteBuffer table = info.bytes();
				int[] index = {0};
				SectionGeometry.forEachBuilt(section -> {
					int i = index[0]++;
					instance(out, i, section.originX() - ox, section.originY() - oy, section.originZ() - oz, i,
							MASK_TERRAIN, section.structureAddress());
					info(table, i, section.vertices().address(), 0, SectionGeometry.VERTEX_STRIDE, section.firstVertex(),
							section.passes());
				});
				if (entityAddress != 0) {
					int i = index[0]++;
					instance(out, i, (float) (cam.x - ox), (float) (cam.y - oy), (float) (cam.z - oz), i, MASK_ENTITIES,
							entityAddress);
					info(table, i, entityVertices.address(), 1, EntityGeometry.VERTEX_STRIDE, new int[3], 0);
				}

				VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
				geometry.get(0).sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
				geometry.get(0).geometry().instances().sType$Default().arrayOfPointers(false);
				geometry.get(0).geometry().instances().data().deviceAddress(instances.address());
				tlasHandle[0] = buildStructure(rt, commands, stack, VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR,
						VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR, geometry, count, release);
				instanceInfo[0] = info;
				instanceCount[0] = count;

				// The scene is complete before anything traces rays through it.
				barrier(commands, stack, VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR,
						VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR | VK_ACCESS_SHADER_READ_BIT,
						VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
						VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
			}
		});
		afterRecord.forEach(Runnable::run);
		release();
		current = new Frame(tlasHandle[0], serial, ox, oy, oz, cam.x, cam.y, cam.z, instanceInfo[0], instanceCount[0],
				camera, release);
	}

	/** Builds an acceleration structure over {@code geometry}; its memory is freed with {@code release}. */
	private static long buildStructure(RtDevice rt, VkCommandBuffer commands, MemoryStack stack, int type, int flags,
			VkAccelerationStructureGeometryKHR.Buffer geometry, int primitives, List<Runnable> release) {
		VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack);
		info.get(0).sType$Default().type(type).flags(flags).mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
				.geometryCount(1).pGeometries(geometry);
		VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
		vkGetAccelerationStructureBuildSizesKHR(rt.device, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0),
				stack.ints(primitives), sizes);
		RtBuffer storage = rt.deviceBuffer(sizes.accelerationStructureSize(),
				VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR);
		long structure = rt.createStructure(storage, type);
		release.add(() -> {
			rt.freeStructure(structure);
			rt.free(storage);
		});
		RtBuffer scratch = rt.scratchBuffer(sizes.buildScratchSize());
		rt.free(scratch);
		info.get(0).dstAccelerationStructure(structure).scratchData().deviceAddress(scratch.address());
		VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
				.primitiveCount(primitives);
		vkCmdBuildAccelerationStructuresKHR(commands, info, stack.pointers(range.address()));
		return structure;
	}

	/** VkAccelerationStructureInstanceKHR: a translation, the custom index and mask, flags, the bottom level. */
	private static void instance(ByteBuffer out, int i, float x, float y, float z, int customIndex, int mask,
			long structureAddress) {
		int at = i * 64;
		float[] transform = {1, 0, 0, x, 0, 1, 0, y, 0, 0, 1, z};
		for (int k = 0; k < 12; k++) {
			out.putFloat(at + k * 4, transform[k]);
		}
		out.putInt(at + 48, customIndex & 0xFFFFFF | mask << 24);
		out.putInt(at + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
		out.putLong(at + 56, structureAddress);
	}

	private static void info(ByteBuffer table, int i, long vertices, int kind, int stride, int[] firstVertex,
			int passes) {
		int at = i * INSTANCE_INFO_SIZE;
		table.putLong(at, vertices);
		table.putInt(at + 8, kind);
		table.putInt(at + 12, stride);
		table.putInt(at + 16, firstVertex[0]);
		table.putInt(at + 20, firstVertex[1]);
		table.putInt(at + 24, firstVertex[2]);
		table.putInt(at + 28, passes);
	}

	static void barrier(VkCommandBuffer commands, MemoryStack stack, int srcAccess, int dstAccess, int srcStage,
			int dstStage) {
		VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack).sType$Default().srcAccessMask(srcAccess)
				.dstAccessMask(dstAccess);
		vkCmdPipelineBarrier(commands, srcStage, dstStage, 0, barrier, null, null);
	}

	/** A line for the status report. */
	public static String status() {
		if (!RayQuery.available()) {
			return "off (no ray queries)";
		}
		if (failed) {
			return "FAILED (" + failure + ")";
		}
		if (!enabled) {
			return "off";
		}
		return String.format(Locale.ROOT, "on: %s; %d entity quads; %d instances; built in %.2f ms of CPU a frame",
				SectionGeometry.status(), lastEntityQuads, current == null ? 0 : current.instanceCount(), buildMillis);
	}
}
