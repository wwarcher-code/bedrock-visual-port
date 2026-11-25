package dev.bedrockvisuals.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.commands.RenderPass;
import dev.bedrockvisuals.BedrockVisualsClient;
import dev.bedrockvisuals.mixin.minecraft.FeatureRenderDispatcherAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The player's own shadow in first person, as Bedrock casts it: Minecraft leaves the camera's entity out of the
 * frame's entities unless the camera is detached, so its body and what it holds cast nothing. Here that entity
 * is extracted anyway and submitted into a frame of its own, prepared by a dispatcher of its own (with its own
 * vertex buffer: preparing a frame uploads the buffer, which would disturb the frame Minecraft has prepared in
 * it). Only the entity shadow pass draws that frame (EntityShadows), so the main view never shows the body.
 */
public final class FirstPersonShadow {
	private static final SubmitNodeStorage STORAGE = new SubmitNodeStorage();
	private static EntityRenderState state;
	private static FeatureRenderDispatcher dispatcher;
	private static StagedVertexBuffer staged;
	private static FeatureRenderDispatcher.PreparedFrame frame;
	private static boolean failed;

	private FirstPersonShadow() {
	}

	/** At the end of Minecraft's entity extraction: the camera's entity, when Minecraft left it out. */
	public static void extract(Camera camera, DeltaTracker delta) {
		state = null;
		if (failed || camera.isDetached()) {
			return;
		}
		Entity entity = camera.entity();
		// A sleeping entity is drawn already (Minecraft keeps it in when the camera lies in a bed).
		if (!(entity instanceof LivingEntity living) || living.isSleeping()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		try {
			boolean frozen = minecraft.level.tickRateManager().isEntityFrozen(entity);
			state = minecraft.levelRenderer.entityRenderDispatcher()
					.extractEntity(entity, delta.getGameTimeDeltaPartialTick(!frozen));
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/** After Minecraft prepares its frame: the camera's entity, prepared into this frame of its own. */
	public static void prepare(CameraRenderState camera) {
		EntityRenderState extracted = state;
		state = null;
		if (failed || extracted == null || camera == null || camera.pos == null) {
			return;
		}
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (dispatcher == null) {
				dispatcher = new FeatureRenderDispatcher(minecraft.gameRenderer.renderBuffers(),
						minecraft.getModelManager(), minecraft.getAtlasManager(), minecraft.font,
						minecraft.gameRenderer.gameRenderState());
				staged = new StagedVertexBuffer(() -> "Bedrock Visuals first-person shadow", 1 << 20);
				((FeatureRenderDispatcherAccessor) dispatcher).bedrockvisuals$setStagedVertexBuffer(staged);
			}
			minecraft.levelRenderer.entityRenderDispatcher().submit(extracted, camera, extracted.x - camera.pos.x,
					extracted.y - camera.pos.y, extracted.z - camera.pos.z, new PoseStack(), STORAGE);
			frame = dispatcher.prepareFrame(STORAGE);
		} catch (RuntimeException e) {
			fail(e);
		}
	}

	/** Into the shadow cascades, after the frame's other entities (EntityShadows). */
	static void executeSolid(RenderPass pass) {
		if (frame != null) {
			frame.executeSolid(pass);
		}
	}

	/** Once Minecraft has closed its frame. */
	public static void endFrame() {
		try {
			if (frame != null) {
				frame.close();
			}
			if (staged != null) {
				staged.endFrame();
			}
		} catch (RuntimeException e) {
			fail(e);
		} finally {
			frame = null;
		}
	}

	private static void fail(RuntimeException e) {
		failed = true;
		state = null;
		frame = null;
		BedrockVisualsClient.LOGGER.error("[shadows] the first-person shadow failed; none until restart", e);
	}
}
