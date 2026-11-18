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
import dev.bedrockvisuals.bedrock.QualitySettings;
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
	/** The finest cascade's texels across (the texel size the lighting pass is given). */
	public static final int RESOLUTION = 2048;
	/** Cascade slots. A preset with fewer cascades fills the last ones, so slot 3 is always the outermost. */
	public static final int CASCADES = 4;
	/** max_shadow_distance (128 at every PC level). */
	public static final float RANGE = 128;
	/**
	 * Blocks per unit of Bedrock's shadow depth. Every cascade is looked up through the one CascadesShadowProj
	 * (GPU capture 1), whose depth row is the light direction at 0.0057868 per block.
	 */
	static final float BEDROCK_DEPTH_BLOCKS = 1f / 0.0057868f;

	/*
	 * Per slot, from the quality preset's shadow level (Quality.shadows, Bedrock's shadow_configuration.pc.json;
	 * ultra, as captured: 4 cascades of 2048² over 1/6, 1/3, 1/2 and all of the range):
	 */
	/** Blocks the cascade covers around the camera (its range × max_shadow_distance). */
	private static final float[] HALF_EXTENTS = new float[CASCADES];
	/** Depth bias in blocks: Bedrock's bias (CascadesParameters.y) in its depth units. */
	private static final float[] BIAS_BLOCKS = new float[CASCADES];
	/** Slope bias in blocks per tan(acos(max(N·L, NdLFloor 0.04))): Bedrock's slope_bias (CascadesParameters.z). */
	private static final float[] SLOPE_BIAS_BLOCKS = new float[CASCADES];
	/** The map's size; 0 for a slot the preset doesn't use. */
	private static final int[] SIZE = new int[CASCADES];
	/** Bedrock's update_frequency and update_offset: drawn every this many frames, staggered. */
	private static final int[] FREQUENCY = new int[CASCADES];
	private static final int[] OFFSET = new int[CASCADES];
	/** Whether the slot's map was drawn this frame (else it's the last one, moved with the camera). */
	private static final boolean[] REDRAWN = new boolean[CASCADES];
	private static final Matrix4f[] DRAWN_MATRIX = {new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f()};
	private static final double[][] DRAWN_CAMERA = new double[CASCADES][3];
	private static final Vector3f[] DRAWN_DIRECTION = new Vector3f[CASCADES];
	private static QualitySettings.Shadows configured;
	private static long frame;
	public static final float DEPTH_RANGE = 256;
	/**
	 * Below this height above the horizon (about 0.3°) the map isn't drawn: the light grazes the terrain too flatly
	 * to shadow usefully, and its share of the light (lightShare) is under a tenth by then.
	 */
	private static final float MIN_ELEVATION = 0.005f;
	/**
	 * Over its last this much height above the horizon (2°) the directional light fades out (lightShare). TUNE:
	 * Bedrock lights the level with one directional light, through its shadow map; at sunset its sun curve is
	 * still at 14 of 100 on the horizon and reaches 0 some 7° below it, and a sun below the horizon is shadowed
	 * by the ground. Without a map that low, a fade stands in.
	 */
	static final float HORIZON_FADE = 0.035f;
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
	private static final java.util.Map<Integer, GpuTexture> unusedColor = new java.util.HashMap<>();
	private static final java.util.Map<Integer, GpuTextureView> unusedColorViews = new java.util.HashMap<>();
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

	/**
	 * The direction towards the light of the last shadow map, while shadows are on (for Sodium's culling, which
	 * keeps sections out of view whose shadows fall into it); null otherwise.
	 */
	public static Vector3f casterDirection() {
		return valid && !disabled ? heldDirection : null;
	}

	/** Whether the moon, not the sun, is Bedrock's one directional light: once the sun is below the horizon. */
	static boolean moonIsTheLight(LightingState light) {
		return light.sunDirection().y <= 0;
	}

	/**
	 * How much of the sun's (or the moon's) light reaches the level as its directional light, 0 to 1: none
	 * from the one that isn't the directional light, and the one that is fading out over its last
	 * {@link #HORIZON_FADE} above the horizon. Bedrock has the one light; here the other lit every face turned
	 * towards it with no shadow at all (at sunset, every block side towards the sunset, the sun under the
	 * horizon).
	 */
	static float lightShare(LightingState light, boolean moon) {
		if (moon != moonIsTheLight(light)) {
			return 0;
		}
		float y = moon ? light.moonDirection().y : light.sunDirection().y;
		return Math.clamp(y / HORIZON_FADE, 0f, 1f);
	}

	/** Whether the map was cast from the moon rather than the sun (or, with no map, would be). */
	public static boolean fromMoon() {
		return fromMoon;
	}

	public static Matrix4fc viewProjection(int cascade) {
		return VIEW_PROJECTION[cascade];
	}

	/** The (unused) colour attachment matching the slot's map, or null before the first map. */
	static GpuTextureView unusedColorView(int cascade) {
		return unusedColorViews.get(Math.max(SIZE[cascade], 1));
	}

	/** Whether the slot's map was drawn this frame: entities are drawn into those only. */
	static boolean redrawn(int cascade) {
		return REDRAWN[cascade];
	}

	/**
	 * Takes the quality preset's shadow level when it changes: its cascades fill the last slots, the maps are
	 * made again at its sizes, and every map is drawn afresh.
	 */
	private static void configure() {
		QualitySettings.Shadows shadows = Quality.shadows();
		if (shadows == configured) {
			return;
		}
		configured = shadows;
		java.util.List<QualitySettings.Cascade> cascades = shadows.cascades();
		int count = Math.min(cascades.size(), CASCADES);
		int first = CASCADES - count;
		for (int slot = 0; slot < CASCADES; slot++) {
			DRAWN_DIRECTION[slot] = null;
			REDRAWN[slot] = false;
			if (slot < first) {
				HALF_EXTENTS[slot] = 0;
				BIAS_BLOCKS[slot] = 0;
				SLOPE_BIAS_BLOCKS[slot] = 0;
				SIZE[slot] = 0;
				FREQUENCY[slot] = 1;
				OFFSET[slot] = 0;
				VIEW_PROJECTION[slot].zero(); // tests as outside: the lookups skip it
				continue;
			}
			QualitySettings.Cascade cascade = cascades.get(slot - first);
			HALF_EXTENTS[slot] = cascade.range() * shadows.maxDistance();
			BIAS_BLOCKS[slot] = cascade.bias() * BEDROCK_DEPTH_BLOCKS;
			SLOPE_BIAS_BLOCKS[slot] = cascade.slopeBias() * BEDROCK_DEPTH_BLOCKS;
			SIZE[slot] = Math.clamp(cascade.resolution(), 64, 8192);
			FREQUENCY[slot] = Math.max(1, cascade.updateFrequency());
			OFFSET[slot] = cascade.updateOffset();
		}
		closeResources();
		reported = false;
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
		configure();
		// Bedrock's one directional light: the sun while it's above the horizon, then the moon.
		boolean moon = moonIsTheLight(light);
		Vector3f current = moon ? light.moonDirection() : light.sunDirection();
		if (moon != fromMoon) {
			heldDirection = null;
		}
		fromMoon = moon;
		if (current.y < MIN_ELEVATION) {
			return;
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
		valid = true;
		if (!reported) {
			reported = true;
			BedrockVisualsClient.LOGGER.info("[shadows] first shadows drawn: {} cascades ({}) out to {} blocks, "
							+ "{} Sodium terrain draws, {} towards {}", configured.cascades().size(),
					java.util.Arrays.toString(SIZE), (int) RANGE, terrainDraws, moon ? "moon" : "sun", towardsLight);
		}
	}

	private static void draw(ChunkSectionsToRender chunks, GpuSampler sampler, GpuTextureView blockAtlas,
			Vector3f towardsLight) {
		ensureResources();
		terrainDraws = 0;
		Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		frame++;
		for (int c = 0; c < CASCADES; c++) {
			REDRAWN[c] = false;
			if (SIZE[c] == 0) {
				continue; // a slot the preset doesn't use
			}
			// Bedrock draws its far cascades every other frame, staggered (update_frequency, update_offset). In
			// between, the last map serves, its projection moved by the camera's travel since (the lookups are
			// camera-relative). A new light direction redraws every map.
			boolean due = DRAWN_DIRECTION[c] == null || !DRAWN_DIRECTION[c].equals(towardsLight)
					|| (frame + OFFSET[c]) % FREQUENCY[c] == 0;
			if (OfflineRender.active()) {
				// An offline frame draws every cascade, once: its later sub-frames see the same level.
				due = !OfflineRender.laterSubframe() || DRAWN_DIRECTION[c] == null;
			}
			if (!due) {
				VIEW_PROJECTION[c].set(DRAWN_MATRIX[c]).translate((float) (camera.x - DRAWN_CAMERA[c][0]),
						(float) (camera.y - DRAWN_CAMERA[c][1]), (float) (camera.z - DRAWN_CAMERA[c][2]));
				continue;
			}
			VIEW_PROJECTION[c].set(ShadowProjection.build(towardsLight, camera.x, camera.y, camera.z, HALF_EXTENTS[c],
					DEPTH_RANGE, SIZE[c]));
			DRAWN_MATRIX[c].set(VIEW_PROJECTION[c]);
			DRAWN_CAMERA[c][0] = camera.x;
			DRAWN_CAMERA[c][1] = camera.y;
			DRAWN_CAMERA[c][2] = camera.z;
			DRAWN_DIRECTION[c] = new Vector3f(towardsLight);
			REDRAWN[c] = true;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				encoder.writeToBuffer(data[c].slice(), Std140Builder.onStack(stack, 64).putMat4f(VIEW_PROJECTION[c]).get());
			}
			int cascade = c;
			RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Bedrock Visuals shadow cascade " + cascade)
					.withColorAttachment(unusedColorView(c))
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
			// An unused slot still binds a (1x1) map: the shaders declare all four.
			int size = Math.max(SIZE[c], 1);
			depth[c] = device.createTexture(() -> "Bedrock Visuals shadow cascade " + cascade,
					GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT,
					size, size, 1, 1);
			depthView[c] = device.createTextureView(depth[c]);
			if (data[c] == null) {
				data[c] = device.createBuffer(() -> "Bedrock Visuals shadow data " + cascade,
						GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64);
			}
			if (!unusedColor.containsKey(size)) {
				GpuTexture color = device.createTexture(() -> "Bedrock Visuals shadow map color (unused) " + size,
						GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.R8_UNORM, size, size, 1, 1);
				unusedColor.put(size, color);
				unusedColorViews.put(size, device.createTextureView(color));
			}
		}
	}

	/** Lets the maps go (a new preset's sizes); they're made again before the next draw. */
	private static void closeResources() {
		for (int c = 0; c < CASCADES; c++) {
			if (depth[c] != null) {
				depthView[c].close();
				depth[c].close();
				depthView[c] = null;
				depth[c] = null;
			}
		}
		unusedColorViews.values().forEach(GpuTextureView::close);
		unusedColor.values().forEach(GpuTexture::close);
		unusedColorViews.clear();
		unusedColor.clear();
	}
}
