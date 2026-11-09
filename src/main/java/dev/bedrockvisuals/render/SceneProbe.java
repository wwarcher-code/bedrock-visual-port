package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
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
import java.util.OptionalDouble;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;

/**
 * The scene in Bedrock's reflection probe: the terrain around the camera, drawn into a G-buffer for each face of
 * a cube and lit (probe/light.fsh) as Bedrock's SkyProbeDeferredShading lights its probe's own G-buffer (its
 * inputs are the probe's ColorMetalnessSubsurface, EmissiveAmbientLinearRoughness, Normal and SceneDepth, and
 * the shadow cascades). The reflection probe's base pass (SkyProbe, sky/probe_base.fsh) puts the sky behind it,
 * and the probe's mips then hold the scene per roughness: reflections that find nothing on screen (the mirror
 * ray leaves the screen, or meets the back of something) reflect the terrain there instead of the sky.
 *
 * <p>Captured as Bedrock regenerates its probe (reflection_probe_regen_configuration.shared.json): every 600
 * frames, every 100 game ticks, after the camera moves 500 blocks, or when the sky's brightness changes by 3.
 * Sodium hands over only the sections the camera sees, so for a few frames before each capture its culling
 * passes every section ({@link #wantsAllSections}), and the faces are drawn from that frame's sections.
 */
public final class SceneProbe {
	static final int FACE_SIZE = 256;
	private static final int REGEN_FRAMES = 600;
	private static final int REGEN_TICKS = 100;
	private static final double REGEN_DISTANCE = 500;
	private static final int REGEN_SKY_CHANGE = 3;
	/** Frames Sodium culls with every section passing before the faces are drawn (its culling can lag a frame). */
	private static final int FRAMES_BEFORE_CAPTURE = 3;
	private static final float NEAR = 0.05f;
	/** Reversed depth: 0 is the far plane. */
	private static final double CLEAR_DEPTH = 0.0;
	private static final Vector4f CLEAR = new Vector4f(0);
	/** The faces' directions and up vectors: +x, -x, +y, -y, +z, -z. */
	private static final Vector3f[] DIRECTIONS = {new Vector3f(1, 0, 0), new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0),
			new Vector3f(0, -1, 0), new Vector3f(0, 0, 1), new Vector3f(0, 0, -1)};
	private static final Vector3f[] UPS = {new Vector3f(0, 1, 0), new Vector3f(0, 1, 0), new Vector3f(0, 0, 1),
			new Vector3f(0, 0, 1), new Vector3f(0, 1, 0), new Vector3f(0, 1, 0)};

	private static final BindGroupLayout LIGHT_LAYOUT = BindGroupLayout.builder()
			.withUniform("ProbeAlbedo", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeNormal", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeMaterial", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeLight", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ProbeDepth", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("ShadowMap3", UniformType.COMBINED_IMAGE_SAMPLER)
			.withUniform("LightingData", UniformType.UNIFORM_BUFFER)
			.withUniform("AtmosphereData", UniformType.UNIFORM_BUFFER)
			.withUniform("GradingData", UniformType.UNIFORM_BUFFER)
			.withUniform("ProbeFace", UniformType.UNIFORM_BUFFER)
			.build();
	private static final RenderPipeline LIGHT = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("bedrockvisuals", "pipeline/scene_probe_light"))
			.withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("bedrockvisuals", "probe/light"))
			.withBindGroupLayout(LIGHT_LAYOUT)
			.withColorTargetState(0, new ColorTargetState(Optional.empty(), BloomPass.HDR_FORMAT, ColorTargetState.WRITE_ALL))
			.withDepthStencilState(Optional.empty())
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build();

	private static final GBuffer[] faceGBuffers = new GBuffer[6];
	private static final GpuTexture[] faces = new GpuTexture[6];
	private static final GpuTextureView[] faceViews = new GpuTextureView[6];
	private static final GpuBuffer[] faceData = new GpuBuffer[6];
	private static final Matrix4f[] faceViewProjection = new Matrix4f[6];
	private static GpuBuffer facesData;
	private static GpuTexture empty;
	private static GpuTextureView emptyView;

	private static boolean wantAll;
	private static int waitFrames;
	private static boolean active;
	private static int activeFace = -1;
	private static boolean facesDrawn;
	private static boolean facesLit;
	private static boolean failed;
	private static long frame;
	private static long capturedFrame = Long.MIN_VALUE / 2;
	private static long capturedTick;
	private static Vec3 capturedAt;
	private static int capturedSkyDarken;

	private SceneProbe() {
	}

	/** True while terrain is drawn into a probe face (the Sodium hook swaps in the probe's pipelines). */
	public static boolean isActive() {
		return active;
	}

	/** True while Sodium should pass every section, ahead of a capture (ViewportMixin). */
	public static boolean wantsAllSections() {
		return wantAll && !failed;
	}

	/** Once a frame, before the shadow and G-buffer passes: decides whether a capture is due. */
	static void beginFrame() {
		frame++;
		if (failed) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		if (wantAll) {
			waitFrames--;
			return;
		}
		Vec3 camera = minecraft.gameRenderer.mainCamera().position();
		boolean due = !facesLit
				|| frame - capturedFrame >= REGEN_FRAMES
				|| level.getGameTime() - capturedTick >= REGEN_TICKS
				|| capturedAt == null || camera.distanceTo(capturedAt) >= REGEN_DISTANCE
				|| Math.abs(level.getSkyDarken() - capturedSkyDarken) >= REGEN_SKY_CHANGE
				|| SettingsBlend.teleported();
		if (due) {
			wantAll = true;
			waitFrames = FRAMES_BEFORE_CAPTURE;
			scheduleCull();
		}
	}

	/** After the main G-buffer pass: draws the faces' G-buffers when a capture is due and Sodium has culled for it. */
	static void capture(ChunkSectionsToRender chunks, GpuSampler sampler, GpuTextureView blockAtlas,
			GpuTextureView materials, org.joml.Matrix4fc mainProjection) {
		if (failed || !wantAll || waitFrames > 0) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		try {
			create();
			Vec3 camera = minecraft.gameRenderer.mainCamera().position();
			float far = (minecraft.options.getEffectiveRenderDistance() + 2) * 16f * 1.5f;
			// Reversed depth (near at 1), and the main projection's handedness on screen (its y may be flipped for
			// the backend), so Sodium's face culling and the lighting's reconstruction hold as they do for the
			// main view.
			Matrix4f projection = new Matrix4f().setPerspective((float) (Math.PI / 2), 1f, far, NEAR, true);
			if (mainProjection.m11() < 0) {
				projection = new Matrix4f().scaling(1, -1, 1).mul(projection);
			}
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			for (int f = 0; f < 6; f++) {
				Matrix4f view = new Matrix4f().setLookAt(0, 0, 0, DIRECTIONS[f].x, DIRECTIONS[f].y, DIRECTIONS[f].z,
						UPS[f].x, UPS[f].y, UPS[f].z);
				faceViewProjection[f] = new Matrix4f(projection).mul(view);
				try (MemoryStack stack = MemoryStack.stackPush()) {
					encoder.writeToBuffer(faceData[f].slice(), Std140Builder.onStack(stack, 128)
							.putMat4f(faceViewProjection[f]).putMat4f(new Matrix4f(faceViewProjection[f]).invert()).get());
				}
				GBuffer gbuffer = faceGBuffers[f];
				RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals scene probe face");
				for (GBuffer.Target target : GBuffer.Target.values()) {
					descriptor.withColorAttachment(gbuffer.view(target), Optional.of(CLEAR));
				}
				descriptor.withDepthAttachment(gbuffer.depthView(), OptionalDouble.of(CLEAR_DEPTH));
				active = true;
				activeFace = f;
				try (RenderPass pass = encoder.createRenderPass(descriptor.build())) {
					RenderSystem.bindDefaultUniforms(pass);
					pass.setUniform("u_MaterialTex", materials, sampler);
					pass.setUniform("ProbeFace", faceData[f]);
					chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, blockAtlas, false);
				} finally {
					active = false;
					activeFace = -1;
				}
			}
			facesDrawn = true;
			capturedFrame = frame;
			capturedTick = level.getGameTime();
			capturedAt = camera;
			capturedSkyDarken = level.getSkyDarken();
		} catch (RuntimeException e) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[sky] scene probe capture failed; reflections fall back to the sky alone", e);
		} finally {
			wantAll = false;
			scheduleCull();
		}
	}

	/**
	 * Before the reflection probe's base pass, with the frame's lighting data written: lights faces drawn this
	 * frame. True when it did (the probe is then drawn again).
	 */
	static boolean lightFaces(CommandEncoder encoder, GpuBuffer lightingData, GpuBuffer atmosphereData) {
		if (!facesDrawn || failed) {
			return false;
		}
		facesDrawn = false;
		try {
			GpuSampler nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
			GpuTextureView shadow = ShadowPass.depthView(ShadowPass.CASCADES - 1);
			for (int f = 0; f < 6; f++) {
				GBuffer gbuffer = faceGBuffers[f];
				int face = f;
				RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals scene probe light")
						.withColorAttachment(faceViews[f], Optional.of(CLEAR))
						.build();
				try (RenderPass pass = encoder.createRenderPass(descriptor)) {
					pass.setPipeline(RenderSystem.getCompiledPipeline(LIGHT));
					pass.setUniform("ProbeAlbedo", gbuffer.view(GBuffer.Target.ALBEDO), nearest);
					pass.setUniform("ProbeNormal", gbuffer.view(GBuffer.Target.NORMAL), nearest);
					pass.setUniform("ProbeMaterial", gbuffer.view(GBuffer.Target.MATERIAL), nearest);
					pass.setUniform("ProbeLight", gbuffer.view(GBuffer.Target.LIGHT), nearest);
					pass.setUniform("ProbeDepth", gbuffer.depthView(), nearest);
					pass.setUniform("ShadowMap3", shadow != null ? shadow : gbuffer.depthView(), nearest);
					pass.setUniform("LightingData", lightingData);
					pass.setUniform("AtmosphereData", atmosphereData);
					pass.setUniform("GradingData", LightingPass.gradingData());
					pass.setUniform("ProbeFace", faceData[face]);
					pass.draw(3, 1, 0, 0);
				}
			}
			try (MemoryStack stack = MemoryStack.stackPush()) {
				Std140Builder builder = Std140Builder.onStack(stack, 6 * 64 + 16);
				for (int f = 0; f < 6; f++) {
					builder.putMat4f(faceViewProjection[f]);
				}
				builder.putVec4(1, 0, 0, 0);
				encoder.writeToBuffer(facesData.slice(), builder.get());
			}
			facesLit = true;
			return true;
		} catch (RuntimeException e) {
			failed = true;
			BedrockVisualsClient.LOGGER.error("[sky] scene probe lighting failed; reflections fall back to the sky alone", e);
			return false;
		}
	}

	/**
	 * Binds the lit faces and their matrices for the reflection probe's base pass (placeholders before a
	 * capture). {@link #ensure} must have run, outside any render pass.
	 */
	static void bindFaces(RenderPass pass) {
		GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		for (int f = 0; f < 6; f++) {
			pass.setUniform("ProbeFace" + f, facesLit && !failed ? faceViews[f] : emptyView, linear);
		}
		pass.setUniform("ProbeFaces", facesData);
	}

	private static void scheduleCull() {
		SodiumWorldRenderer renderer = SodiumWorldRenderer.instanceNullable();
		if (renderer != null) {
			renderer.scheduleTerrainUpdate();
		}
	}

	/** Creates the probe's textures and buffers; outside any render pass (it clears the placeholder face). */
	static void ensure() {
		create();
	}

	private static void create() {
		if (facesData != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
		for (int f = 0; f < 6; f++) {
			faceGBuffers[f] = new GBuffer();
			faceGBuffers[f].ensureSize(FACE_SIZE, FACE_SIZE);
			int face = f;
			faces[f] = device.createTexture(() -> "Bedrock Visuals scene probe face " + face, usage, BloomPass.HDR_FORMAT,
					FACE_SIZE, FACE_SIZE, 1, 1);
			faceViews[f] = device.createTextureView(faces[f]);
			faceData[f] = device.createBuffer(() -> "Bedrock Visuals scene probe face data " + face,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 128);
			faceViewProjection[f] = new Matrix4f();
		}
		empty = device.createTexture(() -> "Bedrock Visuals scene probe empty face", usage, BloomPass.HDR_FORMAT, 1, 1, 1, 1);
		emptyView = device.createTextureView(empty);
		facesData = device.createBuffer(() -> "Bedrock Visuals scene probe faces",
				GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 6 * 64 + 16);
		// Cleared: no faces yet (ProbeFacesParams.x 0), and the placeholder face transparent.
		CommandEncoder encoder = device.createCommandEncoder();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			Std140Builder builder = Std140Builder.onStack(stack, 6 * 64 + 16);
			for (int f = 0; f < 6; f++) {
				builder.putMat4f(new Matrix4f());
			}
			builder.putVec4(0, 0, 0, 0);
			encoder.writeToBuffer(facesData.slice(), builder.get());
		}
		RenderPassDescriptor clear = RenderPassDescriptor.builder(() -> "Bedrock Visuals scene probe empty face")
				.withColorAttachment(emptyView, Optional.of(CLEAR)).build();
		try (RenderPass pass = encoder.createRenderPass(clear)) {
			// Cleared by opening it.
		}
	}

	public static String status() {
		return failed ? "FAILED (see log)" : facesLit ? "terrain captured " + (frame - capturedFrame) + " frames ago, "
				+ FACE_SIZE + " texels a face" : "no capture yet";
	}
}
