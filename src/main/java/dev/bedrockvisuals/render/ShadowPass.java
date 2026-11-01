package dev.bedrockvisuals.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;

/**
 * Renders Sodium's solid and cutout terrain from the sun (or the moon at night) into a depth map that the
 * lighting pass tests against. One map covers {@link #HALF_EXTENT} blocks around the camera; beyond it the
 * lighting falls back to sky light.
 *
 * <p>Known limit: Sodium only hands over the chunks the camera can see, so terrain behind the camera or
 * hidden from it doesn't cast shadows into view.
 */
public final class ShadowPass {
	public static final int RESOLUTION = 2048;
	public static final float HALF_EXTENT = 96;
	public static final float DEPTH_RANGE = 256;
	/** Below this height above the horizon the light grazes the terrain too flatly to shadow usefully. */
	private static final float MIN_ELEVATION = 0.03f;
	/** Reversed depth: 0 is the far side, away from the sun. */
	private static final double CLEAR_DEPTH = 0.0;

	private static GpuTexture depth;
	private static GpuTextureView depthView;
	/**
	 * The pass carries a color attachment beside the depth, matched by the pipeline's color target
	 * ({@link ShadowTerrainPipelines#UNUSED_COLOR}); nothing is written to it.
	 */
	private static GpuTexture unusedColor;
	private static GpuTextureView unusedColorView;
	private static GpuBuffer data;

	private static boolean active;
	private static boolean valid;
	private static boolean fromMoon;
	private static boolean disabled;
	private static boolean reported;
	private static int terrainDraws;
	private static final Matrix4f VIEW_PROJECTION = new Matrix4f();

	private ShadowPass() {
	}

	/** True while terrain is being drawn into the shadow map. */
	public static boolean isActive() {
		return active;
	}

	/** Called by the Sodium hook for each terrain draw in this pass. */
	public static void countTerrainDraw() {
		terrainDraws++;
	}

	/** True when this frame's shadow map holds terrain. */
	public static boolean isValid() {
		return valid;
	}

	public static boolean isDisabled() {
		return disabled;
	}

	/** Whether the map was cast from the moon rather than the sun. */
	public static boolean fromMoon() {
		return fromMoon;
	}

	public static Matrix4fc viewProjection() {
		return VIEW_PROJECTION;
	}

	public static GpuTextureView depthView() {
		return depthView;
	}

	public static void render(ChunkSectionsToRender chunks, GpuSampler sampler, GpuTextureView blockAtlas,
			LightingState light) {
		valid = false;
		if (disabled) {
			return;
		}
		boolean moon = light.sunDirection().y < MIN_ELEVATION;
		Vector3f towardsLight = moon ? light.moonDirection() : light.sunDirection();
		if (towardsLight.y < MIN_ELEVATION) {
			return;
		}
		try {
			draw(chunks, sampler, blockAtlas, towardsLight);
		} catch (RuntimeException e) {
			disabled = true;
			BedrockVisualsClient.LOGGER.error("[shadows] shadow pass failed; shadows disabled until restart", e);
			return;
		}
		fromMoon = moon;
		valid = true;
		if (!reported) {
			reported = true;
			BedrockVisualsClient.LOGGER.info("[shadows] first shadow map drawn: {}x{}, {} blocks around the camera, "
							+ "{} Sodium terrain draws, {} towards {}", RESOLUTION, RESOLUTION, (int) HALF_EXTENT,
					terrainDraws, moon ? "moon" : "sun", towardsLight);
		}
	}

	private static void draw(ChunkSectionsToRender chunks, GpuSampler sampler, GpuTextureView blockAtlas,
			Vector3f towardsLight) {
		ensureResources();
		terrainDraws = 0;
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		VIEW_PROJECTION.set(ShadowProjection.build(towardsLight, camera.x, camera.y, camera.z, HALF_EXTENT,
				DEPTH_RANGE, RESOLUTION));

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			encoder.writeToBuffer(data.slice(), Std140Builder.onStack(stack, 64).putMat4f(VIEW_PROJECTION).get());
		}
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals shadow map")
				.withColorAttachment(unusedColorView)
				.withDepthAttachment(depthView, OptionalDouble.of(CLEAR_DEPTH))
				.build();
		active = true;
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("ShadowData", data);
			chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, blockAtlas, false);
		} finally {
			active = false;
		}
	}

	private static void ensureResources() {
		if (depth != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		depth = device.createTexture(() -> "Bedrock Visuals shadow map",
				GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT,
				RESOLUTION, RESOLUTION, 1, 1);
		depthView = device.createTextureView(depth);
		unusedColor = device.createTexture(() -> "Bedrock Visuals shadow map color (unused)",
				GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.R8_UNORM, RESOLUTION, RESOLUTION, 1, 1);
		unusedColorView = device.createTextureView(unusedColor);
		data = device.createBuffer(() -> "Bedrock Visuals shadow data", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
				64);
	}
}
