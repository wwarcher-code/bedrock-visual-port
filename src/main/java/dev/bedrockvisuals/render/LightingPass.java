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
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
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
	/** std140 size of the LightingData block (include/common.glsl): two mat4s and seventeen vec4s. */
	private static final int DATA_SIZE = 2 * 64 + 17 * 16;
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
			.withUniform("ShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CloudShadowMap", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("CausticsMap", UniformType.COMBINED_IMAGE_SAMPLER)
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
		}
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
			builder.putMat4f(ShadowPass.viewProjection())
					.putVec4(ShadowPass.isValid() ? 1 : 0, 1f / ShadowPass.RESOLUTION, NORMAL_OFFSET_STRENGTH,
							ShadowPass.fromMoon() ? 1 : 0)
					.putVec4(GBufferDebugView.showsShadows() ? 1 : 0, LightingState.METER_SKY_SCALE, 0, 0);
			putVec4(builder, light.skyHorizon(), 0);
			// Modulo 16 keeps float precision far from the origin and still lines up with the 1/16 texel grid.
			Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
			builder.putVec4((float) Math.floorMod((long) Math.floor(camera.x), 16L) + (float) (camera.x - Math.floor(camera.x)),
					(float) Math.floorMod((long) Math.floor(camera.y), 16L) + (float) (camera.y - Math.floor(camera.y)),
					(float) Math.floorMod((long) Math.floor(camera.z), 16L) + (float) (camera.z - Math.floor(camera.z)),
					AutoExposure.exposure());
			CloudShadows.write(builder, light.atmosphere() != null);
			Underwater.write(builder);
			encoder.writeToBuffer(data.slice(), builder.get());
			Std140Builder gradingBuilder = Std140Builder.onStack(stack, ColorGrading.DATA_SIZE);
			ColorGrading.write(gradingBuilder, ColorGrading.current());
			encoder.writeToBuffer(grading.slice(), gradingBuilder.get());
			Std140Builder atmosphereBuilder = Std140Builder.onStack(stack, SkyAtmosphere.DATA_SIZE);
			SkyAtmosphere.write(atmosphereBuilder, light.atmosphere());
			encoder.writeToBuffer(atmosphere.slice(), atmosphereBuilder.get());
		}

		GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
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
			pass.setUniform("ShadowMap", ShadowPass.depthView() != null ? ShadowPass.depthView() : gbuffer.depthView(),
					nearest);
			// Without cloud cells yet the shader skips the lookup (strength 0), but the sampler must be bound.
			pass.setUniform("CloudShadowMap", CloudShadows.view() != null ? CloudShadows.view() : gbuffer.depthView(),
					RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
			pass.setUniform("CausticsMap", Underwater.caustics() != null ? Underwater.caustics() : gbuffer.depthView(),
					nearest);
			pass.setUniform("LightingData", data);
			pass.setUniform("GradingData", grading);
			pass.setUniform("AtmosphereData", atmosphere);
			pass.draw(3, 1, 0, 0);
		}
		LuminanceMeter.measure();
		BloomPass.render(main);
	}

	/** This frame's LightingData buffer, shared with the water shader; null before the first frame. */
	static GpuBuffer lightingData() {
		return data;
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
