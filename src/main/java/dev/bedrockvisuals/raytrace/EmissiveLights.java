package dev.bedrockvisuals.raytrace;

import static org.lwjgl.vulkan.VK12.*;

import dev.bedrockvisuals.render.MaterialAtlas;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

/**
 * The world's glowing blocks as lights: every terrain quad of a block that gives off Java light is an area light,
 * glowing as BlockEmission works out for its block (Bedrock's marked texels, in their colours; or the whole quad),
 * scaled so the whole block gives off as much as its light level (sampled on the GPU from the block atlas and the
 * material atlas at the point picked).
 *
 * <p>The quads are found when a section's structure is built (SectionGeometry, from Sodium's vertices) and kept
 * with it, {@link #FLOATS} floats each, relative to the section's origin:
 * <pre>
 *   p0.xyz, power      the quad's first corner; its power estimate (scaled glow luminance x area x 2)
 *   e1.xyz, reach      the edge to the second corner; how far it lights (blocks)
 *   e2.xyz, cutoff     the edge to the fourth corner; Sodium's alpha cutoff for its texels
 *   uv0.xy, uvE1.xy    the texture coordinates at p0, and along e1
 *   uvE2.xy, glow,     along e2; for a light standing in for block light (BlockEmission's MASKED and ALL_OVER: glow
 *     masked           lichen, lava), its light level / 15 (the surface's own glow, RtEmissive's standInGlow), else 0;
 *                      for MASKED, the brightest marked texel's atlas index plus one (the colour it glows in)
 *   scale, mode, 0, 0  the scale on its glow (BlockEmission) and its mode (BlockEmission.Mode's ordinal)
 * </pre>
 * A point of the quad is p0 + s e1 + t e2 (s, t in [0, 1]): Sodium's quads are parallelograms, in position and
 * texture alike.
 *
 * <p>Each frame the lights near the camera go into a grid of {@link #CELL}-block cells around it, each cell
 * listing the {@link #PER_CELL} lights that light its centre most (power over distance squared). Shaders pick
 * among a cell's lights by that estimate at the point itself, and trace one shadow ray to a random point of the
 * one picked. The grid is world-aligned and rebuilt only when the camera moves to another cell or the lights
 * change; positions in it are relative to its corner ({@link Grid#minX} and so on).
 */
public final class EmissiveLights {
	public static final int FLOATS = 24;
	static final float[] NONE = new float[0];
	/**
	 * 8-block cells, 32 x 32 x 32 of them: 128 blocks each way (a render distance of 8 chunks), up and down too, so
	 * a light's whole reach (MAX_REACH) fits however far the camera is from where the grid was built. At 4-block cells and 48 blocks across, a torch or lava pool farther off lit only its own block (the
	 * lighting pass now hands over to Bedrock's block light past the grid). Each cell still lists the lights that
	 * reach it most; the shaders weigh them at the point itself, so the coarser cells pick about as well.
	 */
	static final int CELL = 8;
	static final int CELLS_X = 32;
	static final int CELLS_Y = 32;
	static final int CELLS_Z = 32;
	/** The lights a cell lists (the shaders' PER_CELL too): 48 let a lava pool and fall crowd a sea lantern out. */
	static final int PER_CELL = 96;
	/**
	 * Below this estimated irradiance (power / distance squared) a light no longer counts; the shaders fade it out
	 * towards there. At 0.004 (a glowstone face's light at 19 blocks, a torch's at 4) the fade was a visible sphere
	 * around each light in a dark cave, with no traced light past it: now 13 times fainter, past what auto
	 * exposure shows.
	 */
	private static final float CUTOFF = 0.0003f;
	private static final float MAX_REACH = 64;
	/** Cells the camera may move from where the grid was built before it is built again. */
	private static final int REBUILD_CELLS = 2;
	/** How far the shaders' jittered cell lookup can land from the point: half a cell's diagonal. */
	private static final float LOOKUP_JITTER = CELL * 0.5f * (float) Math.sqrt(3);
	private static final int MAX_LIGHTS = 1 << 16;
	/** Scaled glow luminance below this (a torch's stick end, with no flame) doesn't make a quad a light. */
	private static final float MIN_GLOW = 1e-4f;

	private static boolean changed = true;
	private static Grid grid;
	private static int lastLights;
	private static double buildMillis;

	/** The lights near the camera, on the GPU. Positions are relative to the grid's corner (world blocks). */
	record Grid(int minX, int minY, int minZ, RtBuffer lights, RtBuffer cells, int lightCount, int materialGeneration) {
	}

	private EmissiveLights() {
	}

	/**
	 * The current grid's lowest corner in world blocks and its size, {minX, minY, minZ, sizeX, sizeY, sizeZ}, or
	 * null before there is one: the lighting pass falls back on Bedrock's block light outside it.
	 */
	public static int[] gridBounds() {
		Grid g = grid;
		return g == null ? null : new int[] {g.minX(), g.minY(), g.minZ(), CELLS_X * CELL, CELLS_Y * CELL, CELLS_Z * CELL};
	}

	/** Some section's lights changed (built, rebuilt or gone). */
	static void changed() {
		changed = true;
	}

	/** The blocks of a section that give off Java light: only their quads are lights (BlockEmission). */
	record Emitters(net.minecraft.client.multiplayer.ClientLevel level, int originX, int originY, int originZ) {
		/** The block at a point given relative to the section's origin. */
		net.minecraft.world.level.block.state.BlockState stateAt(float x, float y, float z) {
			return level.getBlockState(net.minecraft.core.BlockPos.containing(originX + x, originY + y, originZ + z));
		}
	}

	/** The section's light-giving blocks, or null if it has none (a check of its palette). */
	static Emitters emitters(int originX, int originY, int originZ) {
		net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) {
			return null;
		}
		int index = level.getSectionIndexFromSectionY(originY >> 4);
		if (index < 0 || index >= level.getSectionsCount()) {
			return null;
		}
		net.minecraft.world.level.chunk.LevelChunkSection section = level.getChunk(originX >> 4, originZ >> 4)
				.getSection(index);
		if (section.hasOnlyAir() || !section.maybeHas(state -> state.getLightEmission() > 0)) {
			return null;
		}
		return new Emitters(level, originX, originY, originZ);
	}

	/**
	 * Appends the emissive quads among a pass's Sodium vertices (CompactChunkVertex, 20 bytes) to {@code lights}
	 * and returns the result: quads with emissive texels, and quads of blocks that give off light ({@code
	 * emitters}, null if the section has none).
	 */
	static float[] collect(ByteBuffer data, float[] lights, Emitters emitters) {
		if (MaterialAtlas.emissiveWidth() == 0 || emitters == null) {
			return lights;
		}
		int base = data.position();
		int quads = data.remaining() / (4 * SectionGeometry.VERTEX_STRIDE);
		float[] out = lights;
		int count = lights.length / FLOATS;
		float[] p = new float[12];
		float[] uv = new float[8];
		// Fluids draw their surfaces from both sides: one light each, as lights glow both ways anyway.
		java.util.Set<Long> seen = new java.util.HashSet<>();
		for (int q = 0; q < quads; q++) {
			float uMin = 2;
			float uMax = -1;
			float vMin = 2;
			float vMax = -1;
			for (int v = 0; v < 4; v++) {
				int at = base + (q * 4 + v) * SectionGeometry.VERTEX_STRIDE;
				int packed = data.getInt(at + 12);
				uv[v * 2] = (packed & 0x7FFF) / 32768f;
				uv[v * 2 + 1] = (packed >>> 16 & 0x7FFF) / 32768f;
				uMin = Math.min(uMin, uv[v * 2]);
				uMax = Math.max(uMax, uv[v * 2]);
				vMin = Math.min(vMin, uv[v * 2 + 1]);
				vMax = Math.max(vMax, uv[v * 2 + 1]);
			}
			int first = base + q * 4 * SectionGeometry.VERTEX_STRIDE;
			for (int v = 0; v < 4; v++) {
				int at = first + v * SectionGeometry.VERTEX_STRIDE;
				int hi = data.getInt(at);
				int lo = data.getInt(at + 4);
				p[v * 3] = SectionGeometry.position(hi, lo, 0);
				p[v * 3 + 1] = SectionGeometry.position(hi, lo, 10);
				p[v * 3 + 2] = SectionGeometry.position(hi, lo, 20);
			}
			float e1x = p[3] - p[0];
			float e1y = p[4] - p[1];
			float e1z = p[5] - p[2];
			float e2x = p[9] - p[0];
			float e2y = p[10] - p[1];
			float e2z = p[11] - p[2];
			float cx = e1y * e2z - e1z * e2y;
			float cy = e1z * e2x - e1x * e2z;
			float cz = e1x * e2y - e1y * e2x;
			float area = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
			if (area < 1e-6f) {
				continue;
			}
			float mx = p[0] + (e1x + e2x) * 0.5f;
			float my = p[1] + (e1y + e2y) * 0.5f;
			float mz = p[2] + (e1z + e2z) * 0.5f;
			// The block the face belongs to: the one its centre is in, for a face inside a block's cell (a torch's,
			// glow lichen's a hair off its wall, lava's top); for a face on the cells' boundary, the one just behind
			// it, or in front if that one gives no light (a fluid's surface drawn facing the other way).
			float ux = cx / area;
			float uy = cy / area;
			float uz = cz / area;
			boolean axis = Math.abs(ux) > 0.9f || Math.abs(uy) > 0.9f || Math.abs(uz) > 0.9f;
			float across = Math.abs(ux) > 0.9f ? mx : Math.abs(uy) > 0.9f ? my : mz;
			float off = Math.abs(across - Math.round(across));
			net.minecraft.world.level.block.state.BlockState state;
			if (off > 0.002f && axis) {
				state = emitters.stateAt(mx, my, mz);
			} else {
				state = emitters.stateAt(mx - ux * 0.05f, my - uy * 0.05f, mz - uz * 0.05f);
				if (state.getLightEmission() == 0) {
					state = emitters.stateAt(mx + ux * 0.05f, my + uy * 0.05f, mz + uz * 0.05f);
				}
			}
			BlockEmission.Emission emission = BlockEmission.of(state);
			if (emission == null) {
				continue;
			}
			// What this quad gives off: its glow's mean luminance times its area, scaled so the whole block gives
			// off its light level's worth (BlockEmission), doubled as the lighting pass doubles glow.
			float luminance = emission.luminance(uMin, vMin, uMax, vMax) * emission.scale();
			if (luminance < MIN_GLOW) {
				continue;
			}
			long key = (long) Math.round(mx * 64) * 73856093L ^ (long) Math.round(my * 64) * 19349663L
					^ (long) Math.round(mz * 64) * 83492791L ^ (long) Math.round(area * 256) * 2654435761L;
			if (!seen.add(key)) {
				continue;
			}
			float power = luminance * area * 2.0f;
			// From its power alone, as the shaders work it out (reachOf) when they pick a light: a reach of the light
			// level's for dim lights left them picked only within the shorter one, a sphere with nothing past it.
			float reach = Math.min((float) Math.sqrt(power / CUTOFF), MAX_REACH);
			int material = data.getInt(first + 16) >>> 16 & 0xFF;
			float cutoff = switch (material >>> 1 & 3) {
				case 0 -> 0f;
				case 1 -> 0.1f;
				case 2 -> 0.5f;
				default -> 1f;
			};
			if (count * FLOATS + FLOATS > out.length) {
				out = Arrays.copyOf(out, Math.max(out.length * 2, FLOATS * 8));
			}
			int o = count * FLOATS;
			out[o] = p[0];
			out[o + 1] = p[1];
			out[o + 2] = p[2];
			out[o + 3] = power;
			out[o + 4] = e1x;
			out[o + 5] = e1y;
			out[o + 6] = e1z;
			out[o + 7] = reach;
			out[o + 8] = e2x;
			out[o + 9] = e2y;
			out[o + 10] = e2z;
			out[o + 11] = cutoff;
			out[o + 12] = uv[0];
			out[o + 13] = uv[1];
			out[o + 14] = uv[2] - uv[0];
			out[o + 15] = uv[3] - uv[1];
			out[o + 16] = uv[6] - uv[0];
			out[o + 17] = uv[7] - uv[1];
			out[o + 18] = emission.mode() == BlockEmission.Mode.TEXELS ? 0 : emission.level() / 15f;
			out[o + 19] = emission.mode() == BlockEmission.Mode.MASKED ? emission.marked() : 0;
			out[o + 20] = emission.scale();
			out[o + 21] = emission.mode().ordinal();
			out[o + 22] = 0;
			out[o + 23] = 0;
			count++;
		}
		return out.length == count * FLOATS ? out : Arrays.copyOf(out, count * FLOATS);
	}

	/**
	 * The grid around the camera (world position), rebuilt if the camera moved to another cell or the lights
	 * changed. The old grid's buffers are freed once the GPU is done with them.
	 */
	static Grid grid(RtDevice rt, double cameraX, double cameraY, double cameraZ) {
		int minX = Math.floorDiv((int) Math.floor(cameraX), CELL) * CELL - CELLS_X / 2 * CELL;
		int minY = Math.floorDiv((int) Math.floor(cameraY), CELL) * CELL - CELLS_Y / 2 * CELL;
		int minZ = Math.floorDiv((int) Math.floor(cameraZ), CELL) * CELL - CELLS_Z / 2 * CELL;
		// Kept while the camera stays within REBUILD_CELLS cells of where it was built: rebuilding at every cell the
		// camera crossed cost up to 10 ms of CPU a frame near many lava faces, in exports every frame it moved. The
		// grid reaches 128 blocks each way and a light at most 64 (plus the lookup's jitter), so 16 blocks off centre it
		// still covers everything the camera's surroundings take light from.
		if (grid != null && !changed && Math.abs(grid.minX() - minX) <= REBUILD_CELLS * CELL
				&& Math.abs(grid.minY() - minY) <= REBUILD_CELLS * CELL && Math.abs(grid.minZ() - minZ) <= REBUILD_CELLS * CELL
				&& grid.materialGeneration() == MaterialAtlas.generation()) {
			return grid;
		}
		long start = System.nanoTime();
		changed = false;
		if (grid != null) {
			rt.free(grid.lights());
			rt.free(grid.cells());
		}
		grid = build(rt, minX, minY, minZ);
		lastLights = grid.lightCount();
		buildMillis = (System.nanoTime() - start) / 1e6;
		return grid;
	}

	private static Grid build(RtDevice rt, int minX, int minY, int minZ) {
		int maxX = minX + CELLS_X * CELL;
		int maxY = minY + CELLS_Y * CELL;
		int maxZ = minZ + CELLS_Z * CELL;
		// The lights that can reach the grid, relative to its corner.
		float[][] all = {new float[FLOATS * 256]};
		int[] count = {0};
		SectionGeometry.forEachBuilt(section -> {
			float pad = MAX_REACH + LOOKUP_JITTER;
			if (section.lightCount() == 0 || section.originX() + 16 + pad < minX || section.originX() - pad > maxX
					|| section.originY() + 16 + pad < minY || section.originY() - pad > maxY
					|| section.originZ() + 16 + pad < minZ || section.originZ() - pad > maxZ) {
				return;
			}
			float[] lights = section.lights();
			for (int i = 0; i < section.lightCount() && count[0] < MAX_LIGHTS; i++) {
				if ((count[0] + 1) * FLOATS > all[0].length) {
					all[0] = Arrays.copyOf(all[0], all[0].length * 2);
				}
				int o = count[0] * FLOATS;
				System.arraycopy(lights, i * FLOATS, all[0], o, FLOATS);
				all[0][o] += section.originX() - minX;
				all[0][o + 1] += section.originY() - minY;
				all[0][o + 2] += section.originZ() - minZ;
				count[0]++;
			}
		});
		float[] lights = all[0];
		int n = count[0];

		// Each cell's strongest lights at its centre: a light counts in the cells within its reach.
		int cells = CELLS_X * CELLS_Y * CELLS_Z;
		int[] listed = new int[cells * PER_CELL];
		float[] scores = new float[cells * PER_CELL];
		int[] sizes = new int[cells];
		// A full cell's weakest entry, so a light too weak for it is turned away at once: with lights reaching 64
		// blocks a lava cave's 2500 faces each met some 5000 cells, and scanning a full cell's 96 for each took the
		// build to 270 ms.
		int[] weakestAt = new int[cells];
		float[] weakestScore = new float[cells];
		// One z-slice of cells a thread (their lists apart), each going through the lights in order as before.
		java.util.stream.IntStream.range(0, CELLS_Z).parallel().forEach(z -> {
		for (int l = 0; l < n; l++) {
			int o = l * FLOATS;
			float cx = lights[o] + (lights[o + 4] + lights[o + 8]) * 0.5f;
			float cy = lights[o + 1] + (lights[o + 5] + lights[o + 9]) * 0.5f;
			float cz = lights[o + 2] + (lights[o + 6] + lights[o + 10]) * 0.5f;
			float power = lights[o + 3];
			// Listed out to its reach plus the jitter of the shaders' cell lookup (up to half a cell on each axis),
			// so every point the light reaches finds it whichever cell it looks in (the shaders fade it to nothing
			// at its reach).
			float reach = lights[o + 7] + LOOKUP_JITTER;
			int x0 = Math.max((int) Math.floor((cx - reach) / CELL), 0);
			int x1 = Math.min((int) Math.floor((cx + reach) / CELL), CELLS_X - 1);
			int y0 = Math.max((int) Math.floor((cy - reach) / CELL), 0);
			int y1 = Math.min((int) Math.floor((cy + reach) / CELL), CELLS_Y - 1);
			int z0 = Math.max((int) Math.floor((cz - reach) / CELL), 0);
			int z1 = Math.min((int) Math.floor((cz + reach) / CELL), CELLS_Z - 1);
			if (z >= z0 && z <= z1) {
				for (int y = y0; y <= y1; y++) {
					for (int x = x0; x <= x1; x++) {
						// Distance from the light to the nearest point of the cell.
						float dx = Math.max(Math.max(x * CELL - cx, cx - (x + 1) * CELL), 0);
						float dy = Math.max(Math.max(y * CELL - cy, cy - (y + 1) * CELL), 0);
						float dz = Math.max(Math.max(z * CELL - cz, cz - (z + 1) * CELL), 0);
						float d2 = dx * dx + dy * dy + dz * dz;
						if (d2 > reach * reach) {
							continue;
						}
						float score = power / Math.max(d2, 1);
						int cell = (z * CELLS_Y + y) * CELLS_X + x;
						int at = cell * PER_CELL;
						if (sizes[cell] < PER_CELL) {
							listed[at + sizes[cell]] = l;
							scores[at + sizes[cell]] = score;
							sizes[cell]++;
							if (sizes[cell] == PER_CELL) {
								weakest(scores, at, cell, weakestAt, weakestScore);
							}
						} else if (score > weakestScore[cell]) {
							listed[at + weakestAt[cell]] = l;
							scores[at + weakestAt[cell]] = score;
							weakest(scores, at, cell, weakestAt, weakestScore);
						}
					}
				}
			}
		}
		});

		RtBuffer lightBuffer = rt.hostBuffer((long) Math.max(n, 1) * FLOATS * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
		ByteBuffer out = lightBuffer.bytes();
		for (int i = 0; i < n * FLOATS; i++) {
			out.putFloat(i * 4, lights[i]);
		}
		// Per cell, PER_CELL + 1 vec4s: its light count, then per light its centre (xyz) and power (w), for weighing
		// them without reading the lights themselves. After all the cells, each entry's light index (cells x
		// PER_CELL uints), its top bit set for lights that stand in for block light.
		long cellBytes = (long) cells * (PER_CELL + 1) * 16;
		RtBuffer cellBuffer = rt.hostBuffer(cellBytes + (long) cells * PER_CELL * 4, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
		ByteBuffer table = cellBuffer.bytes();
		java.util.stream.IntStream.range(0, cells).parallel().forEach(c -> {
			int at = c * (PER_CELL + 1) * 16;
			table.putInt(at, sizes[c]);
			for (int k = 0; k < sizes[c]; k++) {
				int l = listed[c * PER_CELL + k];
				int o = l * FLOATS;
				int e = at + 16 + k * 16;
				table.putFloat(e, lights[o] + (lights[o + 4] + lights[o + 8]) * 0.5f);
				table.putFloat(e + 4, lights[o + 1] + (lights[o + 5] + lights[o + 9]) * 0.5f);
				table.putFloat(e + 8, lights[o + 2] + (lights[o + 6] + lights[o + 10]) * 0.5f);
				table.putFloat(e + 12, lights[o + 3]);
				table.putInt((int) cellBytes + (c * PER_CELL + k) * 4, l | (lights[o + 18] > 0 ? 0x80000000 : 0));
			}
		});
		return new Grid(minX, minY, minZ, lightBuffer, cellBuffer, n, MaterialAtlas.generation());
	}

	/** Everything goes (the scene was cleared). */
	static void clear() {
		RtDevice rt = RtDevice.get();
		if (grid != null && rt != null) {
			rt.free(grid.lights());
			rt.free(grid.cells());
		}
		grid = null;
		changed = true;
	}

	/** Finds a full cell's weakest entry. */
	private static void weakest(float[] scores, int at, int cell, int[] weakestAt, float[] weakestScore) {
		int weakest = 0;
		for (int k = 1; k < PER_CELL; k++) {
			if (scores[at + k] < scores[at + weakest]) {
				weakest = k;
			}
		}
		weakestAt[cell] = weakest;
		weakestScore[cell] = scores[at + weakest];
	}

	/** Writes every light of the built sections (world position of its centre, power, reach, glow) to a file. */
	public static void dump(java.nio.file.Path file) {
		StringBuilder out = new StringBuilder("x y z power reach glow width height%n".formatted());
		SectionGeometry.forEachBuilt(section -> {
			float[] l = section.lights();
			for (int i = 0; i < section.lightCount(); i++) {
				int o = i * FLOATS;
				out.append(String.format(Locale.ROOT, "%.2f %.2f %.2f %.3f %.1f %.2f %.3f %.3f%n",
						section.originX() + l[o] + (l[o + 4] + l[o + 8]) / 2,
						section.originY() + l[o + 1] + (l[o + 5] + l[o + 9]) / 2,
						section.originZ() + l[o + 2] + (l[o + 6] + l[o + 10]) / 2, l[o + 3], l[o + 7], l[o + 18],
						Math.sqrt(l[o + 4] * l[o + 4] + l[o + 5] * l[o + 5] + l[o + 6] * l[o + 6]),
						Math.sqrt(l[o + 8] * l[o + 8] + l[o + 9] * l[o + 9] + l[o + 10] * l[o + 10])));
			}
		});
		try {
			java.nio.file.Files.writeString(file, out);
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	static String status() {
		return String.format(Locale.ROOT, "%d lights near the camera (grid built in %.1f ms)", lastLights, buildMillis);
	}
}
