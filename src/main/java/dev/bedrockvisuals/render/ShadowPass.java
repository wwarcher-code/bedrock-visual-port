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
 * Renders Sodium's solid and cutout terrain from the sun (or the moon at night) into Bedrock's four shadow
 * cascades, which the lighting pass tests against. Bedrock's PC configuration
 * (renderer/platform_config/pc/shadow_configuration.pc.json, ultra): 4 cascades of 2048², covering 1/6, 1/3,
 * 1/2 and all of max_shadow_distance 128 blocks; a point takes the darkest of the cascades it falls in, and
 * shadows fade out over the last min(range × 0.1, 8) blocks. Beyond that the lighting falls back to sky light.
 *
 * <p>Known limit: Sodium only hands over the chunks the camera can see, so terrain behind the camera or
 * hidden from it doesn't cast shadows into view.
 */
public final class ShadowPass {
	/**
	 * Texels across the map. At 4096 over 192 blocks a texel is 0.047 blocks, finer than the 1/16-block grid the
	 * lighting snaps its lookups to, so where an edge falls within a shadow texel can't move it a whole grid step
	 * the wrong way when the map turns to follow the sun (at 2048 it could, about a third of the time).
	 */
	public static final int RESOLUTION = 2048;
	public static final int CASCADES = 4;
	/** max_shadow_distance. */
	public static final float RANGE = 128;
	/** Blocks each cascade covers around the camera: 1/6, 1/3, 1/2 and all of the range. */
	static final float[] HALF_EXTENTS = {RANGE / 6, RANGE / 3, RANGE / 2, RANGE};
	/**
	 * Blocks per unit of Bedrock's shadow depth. Every cascade is looked up through the one CascadesShadowProj
	 * (GPU capture 1), whose depth row is the light direction at 0.0057868 per block.
	 */
	static final float BEDROCK_DEPTH_BLOCKS = 1f / 0.0057868f;
	/**
	 * Depth bias per cascade, in blocks: Bedrock's CascadesParameters.y (0.000275, 0.0015, 0.00275, 0.003, the
	 * PC ultra configuration, as captured) in its depth units.
	 */
	static final float[] BIAS_BLOCKS = {0.000275f * BEDROCK_DEPTH_BLOCKS, 0.0015f * BEDROCK_DEPTH_BLOCKS,
			0.00275f * BEDROCK_DEPTH_BLOCKS, 0.003f * BEDROCK_DEPTH_BLOCKS};
	/**
	 * Slope bias per cascade, in blocks per tan(acos(max(N·L, NdLFloor 0.04))): CascadesParameters.z (1e-5,
	 * 2e-5, 4e-5, 6e-5) in Bedrock's depth units.
	 */
	static final float[] SLOPE_BIAS_BLOCKS = {1e-5f * BEDROCK_DEPTH_BLOCKS, 2e-5f * BEDROCK_DEPTH_BLOCKS,
			4e-5f * BEDROCK_DEPTH_BLOCKS, 6e-5f * BEDROCK_DEPTH_BLOCKS};
	public static final float DEPTH_RANGE = 256;
	/** Below this height above the horizon the light grazes the terrain too flatly to shadow usefully. */
	private static final float MIN_ELEVATION = 0.03f;
	/** How far the light moves before the shadow map turns to follow it (ShadowProjection.steppedDirection). */
	static final float DIRECTION_STEP = (float) Math.toRadians(360.0 / 1800.0);
	/** The direction the shadow map is drawn from; null until the first map, or after sun and moon swap. */
	private static Vector3f heldDirection;
	/** Reversed depth: 0 is the far side, away from the sun. */
	private static final double CLEAR_DEPTH = 0.0;

	private static final GpuTexture[] depth = new GpuTexture[CASCADES];
	private static final GpuTextureView[] depthView = new GpuTextureView[CASCADES];
	/**
	 * The pass carries a color attachment beside the depth, matched by the pipeline's color target
	 * ({@link ShadowTerrainPipelines#UNUSED_COLOR}); nothing is written to it.
	 */
	private static GpuTexture unusedColor;
	private static GpuTextureView unusedColorView;
	private static final GpuBuffer[] data = new GpuBuffer[CASCADES];

	private static boolean active;
	private static boolean valid;
	private static boolean fromMoon;
	private static boolean disabled;
	private static boolean reported;
	private static int terrainDraws;
	private static final Matrix4f[] VIEW_PROJECTION = {new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f()};

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

	public static Matrix4fc viewProjection(int cascade) {
		return VIEW_PROJECTION[cascade];
	}

	public static GpuTextureView depthView(int cascade) {
		return depthView[cascade];
	}

	/** Each cascade's depth bias in the map's depth units. */
	static float bias(int cascade) {
		return BIAS_BLOCKS[cascade] / (2 * DEPTH_RANGE);
	}

	/** Each cascade's slope bias in the map's depth units, per tan of the light's angle to the normal. */
	static float slopeBias(int cascade) {
		return SLOPE_BIAS_BLOCKS[cascade] / (2 * DEPTH_RANGE);
	}

	public static void render(ChunkSectionsToRender chunks, GpuSampler sampler, GpuTextureView blockAtlas,
			LightingState light) {
		valid = false;
		if (disabled) {
			return;
		}
		boolean moon = light.sunDirection().y < MIN_ELEVATION;
		Vector3f current = moon ? light.moonDirection() : light.sunDirection();
		if (current.y < MIN_ELEVATION) {
			return;
		}
		if (moon != fromMoon) {
			heldDirection = null;
		}
		heldDirection = ShadowProjection.steppedDirection(current, heldDirection, DIRECTION_STEP);
		Vector3f towardsLight = heldDirection;
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
			BedrockVisualsClient.LOGGER.info("[shadows] first shadows drawn: {} cascades of {}x{} out to {} blocks, "
							+ "{} Sodium terrain draws, {} towards {}", CASCADES, RESOLUTION, RESOLUTION, (int) RANGE,
					terrainDraws, moon ? "moon" : "sun", towardsLight);
		}
	}

	private static void draw(ChunkSectionsToRender chunks, GpuSampler sampler, GpuTextureView blockAtlas,
			Vector3f towardsLight) {
		ensureResources();
		terrainDraws = 0;
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		for (int c = 0; c < CASCADES; c++) {
			VIEW_PROJECTION[c].set(ShadowProjection.build(towardsLight, camera.x, camera.y, camera.z, HALF_EXTENTS[c],
					DEPTH_RANGE, RESOLUTION));
			try (MemoryStack stack = MemoryStack.stackPush()) {
				encoder.writeToBuffer(data[c].slice(), Std140Builder.onStack(stack, 64).putMat4f(VIEW_PROJECTION[c]).get());
			}
			int cascade = c;
			RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals shadow cascade " + cascade)
					.withColorAttachment(unusedColorView)
					.withDepthAttachment(depthView[c], OptionalDouble.of(CLEAR_DEPTH))
					.build();
			active = true;
			try (RenderPass pass = encoder.createRenderPass(descriptor)) {
				RenderSystem.bindDefaultUniforms(pass);
				pass.setUniform("ShadowData", data[c]);
				chunks.renderGroup(ChunkSectionLayerGroup.OPAQUE, pass, sampler, blockAtlas, false);
			} finally {
				active = false;
			}
		}
	}

	private static void ensureResources() {
		if (depth[0] != null) {
			return;
		}
		GpuDevice device = RenderSystem.getDevice();
		for (int c = 0; c < CASCADES; c++) {
			int cascade = c;
			depth[c] = device.createTexture(() -> "Bedrock Visuals shadow cascade " + cascade,
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT,
					RESOLUTION, RESOLUTION, 1, 1);
			depthView[c] = device.createTextureView(depth[c]);
			data[c] = device.createBuffer(() -> "Bedrock Visuals shadow data " + cascade,
					GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
		}
		unusedColor = device.createTexture(() -> "Bedrock Visuals shadow map color (unused)",
				GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.R8_UNORM, RESOLUTION, RESOLUTION, 1, 1);
		unusedColorView = device.createTextureView(unusedColor);
	}
}
