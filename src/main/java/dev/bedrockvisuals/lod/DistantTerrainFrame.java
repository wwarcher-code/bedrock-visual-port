package dev.bedrockvisuals.lod;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.joml.Matrix4fc;

/**
 * One frame of distant terrain, as the source drew it.
 *
 * @param color        lit color; level 1 treats it as albedo
 * @param depth        the source's own depth, in its own projection
 * @param normal       world normals, or null when the source doesn't output them (rebuild from depth)
 * @param material     material IDs, or null when the source doesn't output them (use a generic material)
 * @param reversedZ    whether depth runs from 1 at the near plane to 0 at the far plane
 * @param zeroToOneDepth whether clip-space depth is [0, 1] rather than [-1, 1]
 */
public record DistantTerrainFrame(
		GpuTextureView color,
		GpuTextureView depth,
		GpuTextureView normal,
		GpuTextureView material,
		Matrix4fc projection,
		Matrix4fc modelView,
		float nearClip,
		float farClip,
		boolean reversedZ,
		boolean zeroToOneDepth) {
}
