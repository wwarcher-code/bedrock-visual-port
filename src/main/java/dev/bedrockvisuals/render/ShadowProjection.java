package dev.bedrockvisuals.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The sun's orthographic view for the shadow map: maps camera-relative world positions (what Sodium's terrain
 * shader and the lighting pass both work in) to shadow clip space.
 *
 * <p>Clip x and y span the map's width around the camera. Depth is reversed like Minecraft's main view:
 * 1 is towards the sun and 0 away from it, so a greater-or-equal depth test keeps whatever the sun sees
 * first. The view is snapped to whole texels in world space, so shadow edges don't crawl as the camera moves.
 */
public final class ShadowProjection {
	private ShadowProjection() {
	}

	/**
	 * @param towardsSun  unit vector towards the sun
	 * @param cameraX     camera world position, for texel snapping
	 * @param halfExtent  blocks covered on each side of the camera
	 * @param depthRange  blocks covered towards and away from the sun
	 * @param resolution  shadow map width and height in texels
	 */
	public static Matrix4f build(Vector3f towardsSun, double cameraX, double cameraY, double cameraZ,
			float halfExtent, float depthRange, int resolution) {
		Vector3f toSun = new Vector3f(towardsSun).normalize();
		// Any axis not parallel to the sun works as "up"; the sun's path never comes near Z in vanilla.
		Vector3f up = Math.abs(toSun.z) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 0, 1);
		Vector3f right = new Vector3f(up).cross(toSun).normalize();
		Vector3f upInMap = new Vector3f(toSun).cross(right).normalize();

		double texel = 2.0 * halfExtent / resolution;
		float offsetRight = (float) snapOffset(cameraX * right.x + cameraY * right.y + cameraZ * right.z, texel);
		float offsetUp = (float) snapOffset(cameraX * upInMap.x + cameraY * upInMap.y + cameraZ * upInMap.z, texel);

		// Rows: clip x, clip y, depth, w. JOML's constructor takes columns, so write it transposed.
		return new Matrix4f(
				right.x / halfExtent, upInMap.x / halfExtent, toSun.x / (2 * depthRange), 0,
				right.y / halfExtent, upInMap.y / halfExtent, toSun.y / (2 * depthRange), 0,
				right.z / halfExtent, upInMap.z / halfExtent, toSun.z / (2 * depthRange), 0,
				offsetRight / halfExtent, offsetUp / halfExtent, 0.5f, 1);
	}

	/** The camera's position within one texel along an axis, so the texel grid stays fixed in the world. */
	private static double snapOffset(double cameraAlongAxis, double texel) {
		return cameraAlongAxis - Math.floor(cameraAlongAxis / texel) * texel;
	}
}
