package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.bedrock.LocalLights;
import dev.bedrockvisuals.bedrock.Rgba;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryStack;

/**
 * Bedrock's point lights (local_lighting/local_lighting.json, "light_type": "point_light"; point_lights/global.json
 * before 1.21.120): each block a pack declares a point light shines its colour from its centre, out to its
 * Minecraft light level in blocks, onto the lit surfaces around it (the lighting pass's PointLightData). Up to
 * {@link #MAX} of the nearest within {@link #RADIUS} blocks; the blocks are found a slice of chunk sections at a
 * time, the whole area every {@link #SECTIONS_PER_FRAME}-th part a frame.
 *
 * <p>Vanilla's packs declare none; Vibrant Visuals packs can. Bedrock's point lights cast shadows from a cube
 * of depth per light (PointLightShadowTextureAtlas); here the lighting pass walks the blocks between the surface
 * and the light through the light volume's solid-block channel (LightVolume), and past the volume a light reaches
 * only where Minecraft's block light does.
 */
public final class PointLights {
	static final int MAX = 64;
	/** One vec4 of count, then position and radius, colour, for each light. */
	static final int DATA_SIZE = 16 + MAX * 32;
	private static final int RADIUS = 32;
	/** Chunk sections scanned per frame. */
	private static final int SECTIONS_PER_FRAME = 48;
	/**
	 * Radiance at a block's distance for a full-brightness light, in this renderer's units (TUNE: Bedrock's point
	 * light intensity isn't in its files; this puts a torch's light beside it near the block light's).
	 */
	private static final float INTENSITY = 2f;

	private record Light(BlockPos pos, float r, float g, float b, float radius) {
	}

	private static GpuBuffer data;
	private static Map<Block, Rgba> declared;
	private static Object declaredFrom;
	/** Lights found in the scan so far, and those of the last complete scan (uploaded). */
	private static List<Light> scanning = new ArrayList<>();
	private static List<Light> lights = List.of();
	private static int scanIndex;
	private static int scanOriginX;
	private static int scanOriginY;
	private static int scanOriginZ;

	private PointLights() {
	}

	/** Once a frame before the lighting pass: scans the next sections and writes the lights, camera-relative. */
	static void update() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (data == null) {
			data = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals point lights",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, DATA_SIZE);
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		if (level != null) {
			scan(level, camera);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder builder = Std140Builder.onStack(stack, DATA_SIZE);
			List<Light> current = lights;
			int count = Math.min(current.size(), MAX);
			// y: 1 when the coloured block light (BlockLightColors) is there.
			builder.putVec4(count, BlockLightColors.view() != null ? 1 : 0, 0, 0);
			for (int i = 0; i < MAX; i++) {
				if (i < count) {
					Light light = current.get(i);
					builder.putVec4((float) (light.pos().getX() + 0.5 - camera.x), (float) (light.pos().getY() + 0.5 - camera.y),
							(float) (light.pos().getZ() + 0.5 - camera.z), light.radius());
					builder.putVec4(light.r(), light.g(), light.b(), 0);
				} else {
					builder.putVec4(0, 0, 0, 0).putVec4(0, 0, 0, 0);
				}
			}
			RenderSystem.getDevice().createCommandEncoder().writeToBuffer(data.slice(), builder.get());
		}
	}

	static GpuBuffer data() {
		return data;
	}

	/** The blocks the packs declare as point lights, with their colours (looked up again when the packs change). */
	private static Map<Block, Rgba> declared() {
		Object pack = BedrockInputs.pack().orElse(null);
		if (declared == null || pack != declaredFrom) {
			declaredFrom = pack;
			Map<Block, Rgba> blocks = new HashMap<>();
			LocalLights localLights = BedrockInputs.pack().map(p -> p.localLights()).orElse(null);
			if (localLights != null) {
				for (Map.Entry<String, LocalLights.Light> entry : localLights.byBlock().entrySet()) {
					if (!entry.getValue().pointLight()) {
						continue;
					}
					net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(entry.getKey());
					if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
						blocks.put(BuiltInRegistries.BLOCK.getValue(id), entry.getValue().color());
					}
				}
			}
			declared = Map.copyOf(blocks);
		}
		return declared;
	}

	private static void scan(ClientLevel level, Vec3 camera) {
		Map<Block, Rgba> blocks = declared();
		if (blocks.isEmpty()) {
			lights = List.of();
			return;
		}
		int span = (2 * RADIUS) / 16 + 1;
		int total = span * span * span;
		if (scanIndex == 0) {
			scanOriginX = ((int) Math.floor(camera.x) >> 4) - span / 2;
			scanOriginY = ((int) Math.floor(camera.y) >> 4) - span / 2;
			scanOriginZ = ((int) Math.floor(camera.z) >> 4) - span / 2;
			scanning = new ArrayList<>();
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int n = 0; n < SECTIONS_PER_FRAME && scanIndex < total; n++, scanIndex++) {
			int sx = scanOriginX + scanIndex % span;
			int sy = scanOriginY + (scanIndex / span) % span;
			int sz = scanOriginZ + scanIndex / (span * span);
			LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
			if (chunk == null) {
				continue;
			}
			int sectionIndex = chunk.getSectionIndexFromSectionY(sy);
			if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
				continue;
			}
			LevelChunkSection section = chunk.getSections()[sectionIndex];
			if (section.hasOnlyAir() || !section.maybeHas(state -> blocks.containsKey(state.getBlock()))) {
				continue;
			}
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						Rgba color = blocks.get(state.getBlock());
						if (color == null) {
							continue;
						}
						int emission = state.getLightEmission();
						float strength = INTENSITY * Math.max(emission, 1) / 15f;
						pos.set((sx << 4) + x, (sy << 4) + y, (sz << 4) + z);
						scanning.add(new Light(pos.immutable(), color.r() * strength, color.g() * strength,
								color.b() * strength, Math.max(emission, 1)));
					}
				}
			}
		}
		if (scanIndex >= total) {
			scanIndex = 0;
			double cx = camera.x;
			double cy = camera.y;
			double cz = camera.z;
			scanning.sort(Comparator.comparingDouble(l -> l.pos().distToCenterSqr(cx, cy, cz)));
			lights = List.copyOf(scanning.subList(0, Math.min(MAX, scanning.size())));
		}
	}

	public static String status() {
		int declaredCount = declared == null ? 0 : declared.size();
		return declaredCount == 0 ? "none declared by the packs" : lights.size() + " lit of " + declaredCount
				+ " declared block types";
	}
}
