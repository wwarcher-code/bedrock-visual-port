package dev.bedrockvisuals.mixin.sodium;

import dev.bedrockvisuals.render.SceneProbe;
import dev.bedrockvisuals.render.ShadowPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The shadow pass draws Sodium's visible sections from the sun, so a section out of the camera's view (behind
 * the player, above the screen) cast no shadow even where its shadow falls in view. Sodium's section test also
 * passes a section that lies in the shadow map's outer cascade and whose shadow can reach the view: the section
 * moved away from the light, a section at a time, as far as the map reaches, inside the frustum. Those sections
 * are drawn in the main pass as well, off screen.
 */
@Mixin(value = Viewport.class, remap = false)
abstract class ViewportMixin {
	/** Half a section's diagonal, rounded up: how far its blocks reach from its centre. */
	@Unique
	private static final float SECTION_RADIUS = 14f;
	/** A section's half size, with Sodium's margin (Viewport.CHUNK_SECTION_PADDED_RADIUS). */
	@Unique
	private static final float SECTION_HALF = 9.125f;
	/** FrustumIntersection.INTERSECT: part of the box is in view. */
	@Unique
	private static final int INTERSECT = -1;

	@Shadow
	@Final
	private Frustum frustum;
	@Shadow
	@Final
	private CameraTransform transform;

	// The occlusion graph's tests (OcclusionCuller), by the section's centre in block coordinates.
	@Inject(method = "isBoxVisible(III)Z", at = @At("RETURN"), cancellable = true)
	private void bedrockvisuals$shadowCasters(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && bedrockvisuals$castsIntoView(bedrockvisuals$relativeX(x),
				bedrockvisuals$relativeY(y), bedrockvisuals$relativeZ(z), SECTION_HALF)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "isBoxVisibleLooser(III)Z", at = @At("RETURN"), cancellable = true)
	private void bedrockvisuals$shadowCastersLooser(int x, int y, int z, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && bedrockvisuals$castsIntoView(bedrockvisuals$relativeX(x),
				bedrockvisuals$relativeY(y), bedrockvisuals$relativeZ(z), SECTION_HALF)) {
			cir.setReturnValue(true);
		}
	}

	// The section tree's tests (TraversableTree: Sodium's culling when it reuses an earlier frame's graph), by a
	// camera-relative centre and half size: a node of many sections, then each section in it. Without these a
	// caster the graph had passed was dropped again on such frames.
	@Inject(method = "isBoxVisibleDirect(FFFF)Z", at = @At("RETURN"), cancellable = true)
	private void bedrockvisuals$shadowCastersDirect(float x, float y, float z, float half,
			CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValueZ() && bedrockvisuals$castsIntoView(x, y, z, half)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getBoxIntersectionDirect(FFFF)I", at = @At("RETURN"), cancellable = true)
	private void bedrockvisuals$shadowCastersIntersection(float x, float y, float z, float half,
			CallbackInfoReturnable<Integer> cir) {
		// 0 to 5: outside (the plane it lies behind); -1 partly inside, so the tree tests what's in it.
		if (cir.getReturnValueI() >= 0 && bedrockvisuals$castsIntoView(x, y, z, half)) {
			cir.setReturnValue(INTERSECT);
		}
	}

	@Unique
	private float bedrockvisuals$relativeX(int x) {
		return (x - transform.intX) - transform.fracX;
	}

	@Unique
	private float bedrockvisuals$relativeY(int y) {
		return (y - transform.intY) - transform.fracY;
	}

	@Unique
	private float bedrockvisuals$relativeZ(int z) {
		return (z - transform.intZ) - transform.fracZ;
	}

	/**
	 * Whether the box of half size {@code half} centred on the camera-relative point (rx, ry, rz) can cast a
	 * shadow into the view: it lies in the shadow map's outer cascade, and moved away from the light, a step at a
	 * time as far as the map reaches, it meets the frustum.
	 */
	@Unique
	private boolean bedrockvisuals$castsIntoView(float rx, float ry, float rz, float half) {
		// Ahead of a scene probe capture every section passes: the probe sees all around (SceneProbe).
		if (SceneProbe.wantsAllSections()) {
			return true;
		}
		Vector3f l = ShadowPass.casterDirection();
		if (l == null) {
			return false;
		}
		float radius = half <= SECTION_HALF ? SECTION_RADIUS : half * 1.7321f;
		// The map's frame (ShadowProjection.build): depth towards the light, and the outer cascade's square
		// across it. A box outside them casts nothing into the map.
		float depth = rx * l.x + ry * l.y + rz * l.z;
		if (Math.abs(depth) > ShadowPass.DEPTH_RANGE + radius) {
			return false;
		}
		float ux = Math.abs(l.z) > 0.9f ? 1 : 0;
		float uz = 1 - ux;
		// right = up × l, upInMap = l × right
		float riX = -uz * l.y, riY = uz * l.x - ux * l.z, riZ = ux * l.y;
		float riLength = (float) Math.sqrt(riX * riX + riY * riY + riZ * riZ);
		riX /= riLength;
		riY /= riLength;
		riZ /= riLength;
		float upX = l.y * riZ - l.z * riY, upY = l.z * riX - l.x * riZ, upZ = l.x * riY - l.y * riX;
		float across = ShadowPass.RANGE + radius;
		if (Math.abs(rx * riX + ry * riY + rz * riZ) > across || Math.abs(rx * upX + ry * upY + rz * upZ) > across) {
			return false;
		}
		// Its shadow, followed away from the light to the map's far side, in steps of the box's half size (or
		// a section) so the boxes tested along it overlap.
		float step = Math.max(half, 16f);
		float reach = depth + ShadowPass.DEPTH_RANGE + radius;
		for (float t = step; t <= reach; t += step) {
			float x = rx - l.x * t, y = ry - l.y * t, z = rz - l.z * t;
			if (frustum.testAab(x - half, y - half, z - half, x + half, y + half, z + half)) {
				return true;
			}
		}
		return false;
	}
}
