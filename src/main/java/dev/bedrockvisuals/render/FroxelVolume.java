package dev.bedrockvisuals.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/**
 * Bedrock's volumetric fog on its coarse grid: the screen in tiles by depth slices, as the quality preset's fog
 * level sets them (240 x 135 froxels by 48 slices in GPU captures, high at 1080p; VolumeDimensions,
 * VolumeNearFar in GPU captures), filled once a frame before the lighting pass (lighting/volume.fsh) and read
 * there at each pixel's depth (froxels.glsl). It replaces the lighting pass's per-pixel march: the same medium,
 * on Bedrock's grid, so the water's and the air's scattered light blend across the picture as Bedrock's do.
 * The slices sit side by side in a 2D texture, 8 across.
 */
public final class FroxelVolume {
	/** Slices side by side across the atlas. */
	private static final int TILES_X = 8;
	/**
	 * The grid: the screen in tiles of the quality preset's fog level (Bedrock's volumetric_fog_configuration.pc.json:
	 * 64, 16, 8 and 8 pixels, with 32, 64, 48 and 96 slices), computed each frame (writeGrid).
	 */
	private static int cellsX = 240;
	private static int cellsY = 135;
	private static int slices = 48;

	private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
			.withUniform("ShadowMap3", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CloudShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightVolumeMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("WaterColumnMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("RtProbeMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("RtEntityData", UniformType.UNIFORM_BUFFER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
			// Declared by common.glsl, which the shader includes; every declared block must be bound.
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.build();

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/froxel_volume"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "lighting/volume"))
			.withBindGroupLayout(LAYOUT)
			.withColorTargetState(0, new ColorTargetState(Optional.empty(), BloomPass.HDR_FORMAT,
					ColorTargetState.WRITE_ALL))
			.withColorTargetState(1, new ColorTargetState(Optional.empty(), BloomPass.HDR_FORMAT,
					ColorTargetState.WRITE_ALL))
			.withDepthStencilState(Optional.empty())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();

	private static GpuTexture scatter;
	private static GpuTexture transmittance;
	private static GpuTextureView scatterView;
	private static GpuTextureView transmittanceView;
	private static boolean failed;
	private static boolean drawn;

	private FroxelVolume() {
	}

	/**
	 * Fills the grid for this frame. Called with the frame's lighting and atmosphere data written and before
	 * the lighting pass opens.
	 *
	 * @param fallback a texture to bind where a map isn't there yet (the shader skips it then)
	 */
	static void render(CommandEncoder encoder, GpuBuffer lightingData, GpuBuffer atmosphereData,
			GpuTextureView fallback) {
		if (failed) {
			return;
		}
		try {
			create();
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals froxel volume")
					.withColorAttachment(scatterView, Optional.of(new Vector4f(0)))
					.withColorAttachment(transmittanceView, Optional.of(new Vector4f(1)))
					.build();
			try (RenderPass pass = encoder.createRenderPass(descriptor)) {
				pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
				GpuTextureView outer = ShadowPass.depthView(ShadowPass.CASCADES - 1);
				pass.setUniform("ShadowMap3", outer != null ? outer : fallback, nearest);
				pass.setUniform("CloudShadowMap", CloudShadows.view() != null ? CloudShadows.view() : fallback,
						RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
				pass.setUniform("LightVolumeMap", LightVolume.view() != null ? LightVolume.view() : fallback, nearest);
				// Its terrain heights: no sun in the air under the surface beyond the light volume (caves).
				pass.setUniform("WaterColumnMap", WaterColumns.view() != null ? WaterColumns.view() : fallback, nearest);
				// The ray-traced light for the block light the air scatters (rt_probes.glsl), when on.
				var probes = dev.bedrockvisuals.raytrace.EntityProbes.active()
						? dev.bedrockvisuals.raytrace.EntityProbes.view() : null;
				pass.setUniform("RtProbeMap", probes != null ? probes : fallback, nearest);
				pass.setUniform("RtEntityData", LightingPass.rtEntityData());
				pass.setUniform("LightingData", lightingData);
				pass.setUniform("AtmosphereData", atmosphereData);
				pass.setUniform("GradingData", LightingPass.gradingData());
				pass.draw(3, 1, 0, 0);
			}
			drawn = true;
		} catch (RuntimeException e) {
			failed = true;
			drawn = false;
			BedrockVisualsClient.LOGGER.error("[fog] froxel volume failed; volumetric fog off until restart", e);
		}
	}

	private static void create() {
		if (scatter != null) {
			return;
		}
		int width = cellsX * TILES_X;
		int height = cellsY * ((slices + TILES_X - 1) / TILES_X);
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
		scatter = device.createTexture(() -> "Bedrock Visuals froxel scattering", usage, BloomPass.HDR_FORMAT,
				width, height, 1, 1);
		transmittance = device.createTexture(() -> "Bedrock Visuals froxel transmittance", usage,
				BloomPass.HDR_FORMAT, width, height, 1, 1);
		scatterView = device.createTextureView(scatter);
		transmittanceView = device.createTextureView(transmittance);
	}

	/**
	 * Takes this frame's grid (the preset's tile size over the screen, and its slices) and writes it for the
	 * shaders (AtmosphereData's FroxelGrid: cells across and down, slices, slices across the atlas). A new grid
	 * makes the volume's textures again before it's filled.
	 */
	static void writeGrid(com.mojang.blaze3d.buffers.Std140Builder builder, int screenWidth, int screenHeight) {
		dev.bedrockvisuals.bedrock.QualitySettings.VolumetricFog fog = Quality.volumetricFog();
		int x = Math.clamp((screenWidth + Math.max(fog.tileWidth(), 1) - 1) / Math.max(fog.tileWidth(), 1), 1, 1024);
		int y = Math.clamp((screenHeight + Math.max(fog.tileHeight(), 1) - 1) / Math.max(fog.tileHeight(), 1), 1, 1024);
		int s = Math.clamp(fog.depthSlices(), 4, 128);
		if (x != cellsX || y != cellsY || s != slices) {
			cellsX = x;
			cellsY = y;
			slices = s;
			close();
		}
		builder.putVec4(cellsX, cellsY, slices, TILES_X);
	}

	private static void close() {
		if (scatter != null) {
			scatterView.close();
			transmittanceView.close();
			scatter.close();
			transmittance.close();
			scatter = null;
			transmittance = null;
			scatterView = null;
			transmittanceView = null;
		}
	}

	/** Whether this frame's grid is there to read (else the lighting pass leaves the fog out). */
	static boolean ready() {
		return drawn && !failed;
	}

	static GpuTextureView scatterView() {
		return scatterView;
	}

	static GpuTextureView transmittanceView() {
		return transmittanceView;
	}

	public static String status() {
		return failed ? "FAILED (see log)" : drawn ? "on, " + cellsX + "x" + cellsY + "x" + slices + " froxels" : "not drawn yet";
	}
}
