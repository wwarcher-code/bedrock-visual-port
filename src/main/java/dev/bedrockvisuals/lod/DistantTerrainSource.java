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
}
