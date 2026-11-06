package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import dev.bedrockvisuals.lod.DistantTerrain;
import dev.bedrockvisuals.lod.DistantTerrainFrame;
import dev.bedrockvisuals.lod.DistantTerrainSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * Lights the G-buffer into the main target with one full-screen triangle. Pixels the G-buffer has no
 * terrain for (depth still at the cleared far plane) are sky: Bedrock's sky added onto Java's sun, moon and
 * stars (SkyAtmosphere), or Java's whole sky left as it is.
 */
public final class LightingPass {
	/** std140 size of the LightingData block (include/common.glsl): three mat4s and seventeen vec4s. */
	private static final int DATA_SIZE = 6 * 64 + 21 * 16;
	/**
	 * Bedrock's shadow normal offset (shadow_configuration.pc.json normal_offset_strength): lookups move out along
	 * the normal by this many blocks times (1 - N·L).
	 */
	private static final float NORMAL_OFFSET_STRENGTH = 0.065f;

	private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
			.withUniform("Albedo", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Normal", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Material", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Light", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("Depth", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowMap0", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowMap1", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowMap2", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowMap3", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CloudShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CausticsMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("WaterColumnMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("BrdfLut", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CloudCoverage", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("DistantColor", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("DistantDepth", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("DistantWaterDepth", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("DistantData", UniformType.UNIFORM_BUFFER)
			.withUniform("PreviousScene", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
			.build();
	/** Premultiplied alpha: terrain (alpha 1) replaces the main image, sky (alpha 0) adds onto it. */
	private static final BlendFunction PREMULTIPLIED = new BlendFunction(BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA);

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/deferred_lighting"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "lighting/deferred"))
			.withBindGroupLayout(LAYOUT)
			.withColorTargetState(0, new ColorTargetState(Optional.of(PREMULTIPLIED),
					ColorTargetState.DEFAULT.format(), ColorTargetState.WRITE_ALL))
			.withColorTargetState(1, new ColorTargetState(Optional.empty(), LuminanceMeter.FORMAT,
					ColorTargetState.WRITE_ALL))
			.withColorTargetState(2, new ColorTargetState(Optional.empty(), BloomPass.HDR_FORMAT,
					ColorTargetState.WRITE_ALL))
			.withDepthStencilState(Optional.empty())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();

	private static GpuBuffer data;
	private static GpuBuffer grading;
	private static GpuBuffer atmosphere;
	/** DistantData (lighting/deferred.fsh): Distant Horizons' inverse projection and flags. */
	private static GpuBuffer distant;
	/** This frame's Distant Horizons depth, or null when its terrain isn't lit here. */
	private static GpuTextureView distantDepth;
	private static final int DISTANT_SIZE = 64 + 32;

	private LightingPass() {
	}

	/** @param projection, modelView the matrices Sodium drew the G-buffer terrain with */
	public static void render(RenderTarget main, GBuffer gbuffer, Matrix4fc projection, Matrix4fc modelView,
			LightingState light) {
		if (data == null) {
			data = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals lighting data",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, DATA_SIZE);
			grading = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals grading data",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, ColorGrading.DATA_SIZE);
			atmosphere = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals atmosphere data",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SkyAtmosphere.DATA_SIZE);
			distant = RenderSystem.getDevice().createBuffer(() -> "Bedrock Visuals distant terrain data",
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, DISTANT_SIZE);
		}
		// Distant Horizons' terrain this frame, lit here where vanilla terrain doesn't cover the pixel.
		DistantTerrainFrame frame = DistantTerrain.source().filter(s -> DeferredRenderer.ownsDistantComposite())
				.flatMap(DistantTerrainSource::latestFrame).orElse(null);
		distantDepth = frame != null ? frame.depth() : null;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		Matrix4f inverseViewProjection = new Matrix4f(projection).mul(modelView).invert();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder builder = Std140Builder.onStack(stack, DATA_SIZE)
					.putMat4f(inverseViewProjection);
			putVec4(builder, light.sunDirection(), 0);
			putVec4(builder, light.sun(), 0);
			putVec4(builder, light.moonDirection(), 0);
			putVec4(builder, light.moon(), 0);
			putVec4(builder, light.sky(), 0);
			putVec4(builder, light.ambient(), 0);
			putVec4(builder, light.blockLight(), 0);
			for (int c = 0; c < ShadowPass.CASCADES; c++) {
				builder.putMat4f(ShadowPass.viewProjection(c));
			}
			builder.putVec4(ShadowPass.isValid() ? 1 : 0, 1f / ShadowPass.RESOLUTION, NORMAL_OFFSET_STRENGTH,
							ShadowPass.fromMoon() ? 1 : 0)
					.putVec4(ShadowPass.bias(0), ShadowPass.bias(1), ShadowPass.bias(2), ShadowPass.bias(3))
					.putVec4(ShadowPass.slopeBias(0), ShadowPass.slopeBias(1), ShadowPass.slopeBias(2),
							ShadowPass.slopeBias(3))
					.putVec4(GBufferDebugView.shadowDebugMode(), LightingState.METER_SKY_SCALE,
							BrdfLut.view() != null ? 1 : 0, GBufferDebugView.postDebugMode());
			putVec4(builder, light.skyHorizon(), 0);
			// Modulo 16 keeps float precision far from the origin and still lines up with the 1/16 texel grid.
			Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
			builder.putVec4((float) Math.floorMod((long) Math.floor(camera.x), 16L) + (float) (camera.x - Math.floor(camera.x)),
					(float) Math.floorMod((long) Math.floor(camera.y), 16L) + (float) (camera.y - Math.floor(camera.y)),
					(float) Math.floorMod((long) Math.floor(camera.z), 16L) + (float) (camera.z - Math.floor(camera.z)),
					AutoExposure.exposure());
			CloudShadows.write(builder, light.atmosphere() != null);
			Underwater.write(builder, light.sunIlluminance());
			WaterColumns.write(builder);
			builder.putMat4f(new Matrix4f(projection).mul(modelView));
			encoder.writeToBuffer(data.slice(), builder.get());
			Std140Builder gradingBuilder = Std140Builder.onStack(stack, ColorGrading.DATA_SIZE);
			ColorGrading.write(gradingBuilder, ColorGrading.current());
			encoder.writeToBuffer(grading.slice(), gradingBuilder.get());
			Std140Builder distantBuilder = Std140Builder.onStack(stack, DISTANT_SIZE);
			if (frame != null) {
				distantBuilder.putMat4f(new Matrix4f(frame.projection()).invert())
						.putVec4(1, frame.reversedZ() ? 1 : 0, frame.zeroToOneDepth() ? 1 : 0,
								frame.opaqueDepth() != null ? 1 : 0);
			} else {
				distantBuilder.putMat4f(new Matrix4f()).putVec4(0, 0, 0, 0);
			}
			Vector3f lightmap = WorldLightmap.fullSky();
			distantBuilder.putVec4(lightmap.x, lightmap.y, lightmap.z, 0);
			encoder.writeToBuffer(distant.slice(), distantBuilder.get());
			Std140Builder atmosphereBuilder = Std140Builder.onStack(stack, SkyAtmosphere.DATA_SIZE);
			SkyAtmosphere.write(atmosphereBuilder, light.atmosphere());
			encoder.writeToBuffer(atmosphere.slice(), atmosphereBuilder.get());
		}

		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
		// Resolved before the pass opens: a placeholder may need creating and uploading, which can't happen
		// while a render pass is open.
		GpuTextureView coverage = CloudShadows.coverageOrEmpty();
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals deferred lighting")
				.withColorAttachment(main.getColorTextureView(), Optional.empty())
				.withColorAttachment(LuminanceMeter.target(main.width, main.height), Optional.of(new Vector4f(0)))
				.withColorAttachment(BloomPass.hdrTarget(main.width, main.height), Optional.of(new Vector4f(0)))
				.build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
			pass.setUniform("Albedo", gbuffer.view(GBuffer.Target.ALBEDO), nearest);
			pass.setUniform("Normal", gbuffer.view(GBuffer.Target.NORMAL), nearest);
			pass.setUniform("Material", gbuffer.view(GBuffer.Target.MATERIAL), nearest);
			pass.setUniform("Light", gbuffer.view(GBuffer.Target.LIGHT), nearest);
			pass.setUniform("Depth", gbuffer.depthView(), nearest);
			// Without a map this frame the shader ignores the binding, but every declared sampler must be bound.
			for (int c = 0; c < ShadowPass.CASCADES; c++) {
				pass.setUniform("ShadowMap" + c,
						ShadowPass.depthView(c) != null ? ShadowPass.depthView(c) : gbuffer.depthView(), nearest);
			}
			// Without cloud cells yet the shader skips the lookup (strength 0), but the sampler must be bound.
			pass.setUniform("CloudShadowMap", CloudShadows.view() != null ? CloudShadows.view() : gbuffer.depthView(),
					RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
			pass.setUniform("CausticsMap", Underwater.caustics() != null ? Underwater.caustics() : gbuffer.depthView(),
					nearest);
			// Without a map yet the shader skips the lookup (WaterColumnOrigin.w 0).
			pass.setUniform("WaterColumnMap", WaterColumns.view() != null ? WaterColumns.view() : gbuffer.depthView(),
					nearest);
			// Without Bedrock's table the shader uses Schlick's approximation (DebugParams.z 0).
			pass.setUniform("BrdfLut", BrdfLut.view() != null ? BrdfLut.view() : gbuffer.depthView(),
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			// Screen-space reflections read the previous frame's HDR scene (this frame's is being drawn).
			GpuTextureView previous = BloomPass.previousHdr();
			pass.setUniform("PreviousScene", previous != null ? previous : gbuffer.depthView(),
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.setUniform("LightingData", data);
			pass.setUniform("GradingData", grading);
			pass.setUniform("AtmosphereData", atmosphere);
			// With DH's terrain from before its water (frame.opaqueColor), the terrain is lit from that and the water
			// surface shaded over it where the final depth is nearer (DistantParams.w).
			GpuTextureView distantColor = frame == null ? null
					: frame.opaqueColor() != null ? frame.opaqueColor() : frame.color();
			GpuTextureView distantGround = frame == null ? null
					: frame.opaqueDepth() != null ? frame.opaqueDepth() : frame.depth();
			pass.setUniform("DistantColor", distantColor != null ? distantColor : gbuffer.depthView(), nearest);
			pass.setUniform("DistantDepth", distantGround != null ? distantGround : gbuffer.depthView(), nearest);
			pass.setUniform("DistantWaterDepth", frame != null ? frame.depth() : gbuffer.depthView(), nearest);
			pass.setUniform("DistantData", distant);
			pass.setUniform("CloudCoverage", coverage,
					RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR, true));
			pass.draw(3, 1, 0, 0);
		}
		LuminanceMeter.measure();
		BloomPass.render(main, LocalExposure.render(main, BloomPass.hdrView()));
		BloomPass.keepForNextFrame();
	}

	/** This frame's LightingData buffer, shared with the water shader; null before the first frame. */
	static GpuBuffer lightingData() {
		return data;
	}

	/** DistantData, shared with the cloud shader (which hides Java's cloud mesh behind DH's terrain). */
	static GpuBuffer distantData() {
		return distant;
	}

	/** Distant Horizons' depth this frame, or null when its terrain isn't lit here. */
	static GpuTextureView distantDepth() {
		return distantDepth;
	}

	static GpuBuffer atmosphereData() {
		return atmosphere;
	}

	static GpuBuffer gradingData() {
		return grading;
	}

	private static void putVec4(Std140Builder builder, Vector3f xyz, float w) {
		builder.putVec4(xyz.x, xyz.y, xyz.z, w);
	}
}
