package dev.bedrockvisuals.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class ShadowProjectionTest {
	private static final float HALF_EXTENT = 96;
	private static final float DEPTH_RANGE = 256;
	private static final int RESOLUTION = 2048;
	private static final Vector3f MORNING_SUN = new Vector3f(1, 1, 0).normalize();

	private static Vector4f project(Matrix4f matrix, float x, float y, float z) {
		return matrix.transform(new Vector4f(x, y, z, 1));
	}

	@Test
	void depthIsGreaterTowardsTheSun() {
		Matrix4f matrix = ShadowProjection.build(MORNING_SUN, 0, 64, 0, HALF_EXTENT, DEPTH_RANGE, RESOLUTION);
		float camera = project(matrix, 0, 0, 0).z;
		float towardsSun = project(matrix, 10, 10, 0).z;
		assertEquals(0.5f, camera, 1e-5f);
		assertTrue(towardsSun > camera);
		assertTrue(project(matrix, -10, -10, 0).z < camera);
	}

	@Test
	void coversTheExtentAroundTheCamera() {
		Matrix4f matrix = ShadowProjection.build(new Vector3f(0, 1, 0), 0, 0, 0, HALF_EXTENT, DEPTH_RANGE, RESOLUTION);
		// Straight-down sun: horizontal offsets of the full extent land on the clip-space edges.
		assertEquals(1, Math.abs(project(matrix, HALF_EXTENT, 0, 0).x), 1e-5f);
		assertEquals(1, Math.abs(project(matrix, 0, 0, HALF_EXTENT).y), 1e-5f);
		assertEquals(0.5f + 0.5f * 10 / DEPTH_RANGE, project(matrix, 0, 10, 0).z, 1e-5f);
	}

	@Test
	void aWorldPointStaysOnItsTexelAsTheCameraMoves() {
		float texelClip = 2f / RESOLUTION;
		double worldX = 1000.3;
		double worldZ = -517.8;
		Float firstTexelOffset = null;
		for (double cameraX = 1000; cameraX < 1001; cameraX += 0.037) {
			Matrix4f matrix = ShadowProjection.build(MORNING_SUN, cameraX, 70, -520, HALF_EXTENT, DEPTH_RANGE,
					RESOLUTION);
			Vector4f clip = project(matrix, (float) (worldX - cameraX), 0, (float) (worldZ + 520));
			// Distance from the point to the texel grid: constant when the grid is fixed in the world.
			float texelOffset = (float) ((clip.x / texelClip) - Math.floor(clip.x / texelClip));
			if (firstTexelOffset == null) {
				firstTexelOffset = texelOffset;
			}
			assertEquals(firstTexelOffset, texelOffset, 1e-2f);
		}
	}

	@Test
	void theShadowDirectionHoldsUntilTheLightMovesAStep() {
		float step = (float) Math.toRadians(0.25);
		org.joml.Vector3f held = ShadowProjection.steppedDirection(new org.joml.Vector3f(0, 1, 0), null, step);
		// A tenth of a degree later: the map keeps its direction (the same instance).
		org.joml.Vector3f slightly = new org.joml.Vector3f((float) Math.sin(Math.toRadians(0.1)), (float) Math.cos(Math.toRadians(0.1)), 0);
		org.junit.jupiter.api.Assertions.assertSame(held, ShadowProjection.steppedDirection(slightly, held, step));
		// Half a degree later: it turns to the light.
		org.joml.Vector3f further = new org.joml.Vector3f((float) Math.sin(Math.toRadians(0.5)), (float) Math.cos(Math.toRadians(0.5)), 0);
		org.joml.Vector3f turned = ShadowProjection.steppedDirection(further, held, step);
		org.junit.jupiter.api.Assertions.assertEquals(0, turned.angle(further), 1e-5);
	}
}
