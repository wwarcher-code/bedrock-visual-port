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
 * Bedrock's volumetric fog on its coarse grid: 240 x 135 froxels by 48 depth slices (VolumeDimensions,
 * VolumeNearFar in GPU captures), filled once a frame before the lighting pass (lighting/volume.fsh) and read
 * there at each pixel's depth (froxels.glsl). It replaces the lighting pass's per-pixel march: the same medium,
 * on Bedrock's grid, so the water's and the air's scattered light blend across the picture as Bedrock's do.
 * The 48 slices sit side by side in a 2D texture, 8 across and 6 down.
 */
public final class FroxelVolume {
	private static final int CELLS_X = 240;
	private static final int CELLS_Y = 135;
	private static final int WIDTH = CELLS_X * 8;
	private static final int HEIGHT = CELLS_Y * 6;

	private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
			.withUniform("ShadowMap3", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CloudShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("WaterColumnMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightVolumeMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
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
				pass.setUniform("WaterColumnMap", WaterColumns.view() != null ? WaterColumns.view() : fallback, nearest);
				pass.setUniform("LightVolumeMap", LightVolume.view() != null ? LightVolume.view() : fallback, nearest);
				pass.setUniform("LightingData", lightingData);
				pass.setUniform("AtmosphereData", atmosphereData);
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
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
		scatter = device.createTexture(() -> "Bedrock Visuals froxel scattering", usage, BloomPass.HDR_FORMAT,
				WIDTH, HEIGHT, 1, 1);
		transmittance = device.createTexture(() -> "Bedrock Visuals froxel transmittance", usage,
				BloomPass.HDR_FORMAT, WIDTH, HEIGHT, 1, 1);
		scatterView = device.createTextureView(scatter);
		transmittanceView = device.createTextureView(transmittance);
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
		return failed ? "FAILED (see log)" : drawn ? "on, " + CELLS_X + "x" + CELLS_Y + "x48 froxels" : "not drawn yet";
	}
}
