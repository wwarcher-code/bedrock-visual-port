package dev.bedrockvisuals.lod;

import java.util.Optional;

/** Holds the active distant terrain source, if a supported LOD mod is loaded. */
public final class DistantTerrain {
	private static volatile DistantTerrainSource source;

	private DistantTerrain() {
	}

	public static Optional<DistantTerrainSource> source() {
		return Optional.ofNullable(source);
	}

	public static void setSource(DistantTerrainSource newSource) {
		source = newSource;
	}
}
