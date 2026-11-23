package dev.bedrockvisuals.lod;

import java.util.Optional;

/**
 * A mod that draws terrain beyond Sodium's render distance. The renderer composites its output into the
 * G-buffer as a reduced material layer, so supporting another LOD mod (Voxy, once it runs on Vulkan) means
 * one new implementation and nothing downstream.
 */
public interface DistantTerrainSource {
	String name();

	/** The source's output for the current frame, or empty before it has drawn one. */
	Optional<DistantTerrainFrame> latestFrame();

	/**
	 * When true the source stops compositing onto Minecraft's frame and stops drawing its own fog, because
	 * the renderer composites and fogs its output instead. Leave false until that composite exists.
	 */
	void setRendererOwnsComposite(boolean owns);

	/**
	 * Whether the near terrain should get the usual fog at the edge of the render distance: when the source
	 * isn't drawing, or its own setting asks for vanilla fog. Otherwise its terrain continues past that edge
	 * and the fog would leave a ring of sky in front of it.
	 */
	default boolean wantsRenderDistanceFog() {
		return false;
	}

	/** How far the source draws terrain, in blocks; 0 when unknown. */
	default int renderDistanceBlocks() {
		return 0;
	}

	/**
	 * Hides (or restores) the source's own clouds for this session, without changing its saved settings:
	 * this renderer lights Minecraft's clouds as Bedrock does, and the source's clouds would be drawn unlit
	 * next to them.
	 */
	default void hideOwnClouds(boolean hide) {
	}

	/** Returned by {@link #topSolidY} where the source has no data. */
	int NO_HEIGHT = Integer.MIN_VALUE;

	/**
	 * The height of the top of the highest solid (not air, not liquid) terrain in the column at block x, z, from
	 * the source's own data, or {@link #NO_HEIGHT}. Called from a background thread, for the far terrain's
	 * shadows (DistantHeights).
	 */
	default int topSolidY(int x, int z) {
		return NO_HEIGHT;
	}

	/**
	 * {@link #topSolidY} and that block's colour, packed as {@code (y << 32) | rgb} (rgb 0xRRGGBB in sRGB, 0 when
	 * unknown). Called from the same background thread, for the far terrain the reflection probe sees.
	 */
	default long topSolid(int x, int z) {
		return (long) topSolidY(x, z) << 32;
	}

	/**
	 * For the column {@link #topSolid} last read on this thread: the bottom of the solid run its top block is
	 * part of (the underside of a floating build or an overhang), or {@link #NO_HEIGHT} when unknown. Where it is
	 * above the ground, the column is open beneath it.
	 */
	default int lastUndersideY() {
		return NO_HEIGHT;
	}

	/**
	 * Lets go of whatever the source keeps for {@link #topSolidY} (Distant Horizons: its data cache, which holds
	 * terrain data checked out of its pools). Called by the same background thread after each tile it reads.
	 */
	default void releaseHeightCache() {
	}

	/** How the source's water is drawn, for diagnostics. */
	default String waterStatus() {
		return "as the source draws it";
	}
}
