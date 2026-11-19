package dev.bedrockvisuals.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;

/**
 * The only class that touches Sodium internals. Mixins forward here, so a Sodium update breaks one file.
 *
 * <p>Spike 1a: reports, once per terrain pass, what Sodium draws into. The G-buffer redirect replaces
 * the pass's target here in step 2.
 */
public final class SodiumAdapter {
	private static final Set<TerrainRenderPass> REPORTED = ConcurrentHashMap.newKeySet();

	private SodiumAdapter() {
	}

	/** Sodium's terrain passes, in the order the ray-traced scene numbers them (raytrace/SectionGeometry). */
	private static final TerrainRenderPass[] RAY_TRACED_PASSES = {DefaultTerrainRenderPasses.SOLID,
			DefaultTerrainRenderPasses.CUTOUT, DefaultTerrainRenderPasses.TRANSLUCENT};

	/**
	 * A section's new mesh, before Sodium uploads it: hands each pass's vertices to the ray-traced scene. They are
	 * Sodium's CompactChunkVertex, 20 bytes: the position's high and low 10 bits an axis (two words; 20 bits map
	 * [-8, 24) blocks from the section's origin), the colour (RGBA8: biome tint times ambient occlusion), the
	 * texture coordinates (two 16-bit values: 15 bits of 1/32768 and a rounding bit), then light (block, sky) and
	 * material bytes.
	 */
	public static void onSectionBuilt(RenderSection section, ChunkBuildOutput output) {
		if (!dev.bedrockvisuals.raytrace.RtScene.enabled()) {
			return;
		}
		java.nio.ByteBuffer[] passes = new java.nio.ByteBuffer[RAY_TRACED_PASSES.length];
		for (int i = 0; i < passes.length; i++) {
			BuiltSectionMeshParts mesh = output.getMesh(RAY_TRACED_PASSES[i]);
			passes[i] = mesh == null ? null : mesh.getVertexData().getDirectBuffer();
		}
		dev.bedrockvisuals.raytrace.SectionGeometry.onSectionBuilt(section.getChunkX(), section.getChunkY(),
				section.getChunkZ(), passes);
	}

	public static void onTerrainPass(TerrainRenderPass pass, RenderPass renderPass) {
		if (REPORTED.add(pass)) {
			BedrockVisualsClient.LOGGER.info("[spike 1a] Sodium terrain pass {} (translucent={}) target={} backend={}",
					pass.getPipeline(), pass.isTranslucent(), pass.getTarget(), RenderSystem.getBackendDescription());
		}
	}
}
