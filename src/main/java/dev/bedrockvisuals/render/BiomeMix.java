package dev.bedrockvisuals.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;

/**
 * Which biomes' settings are in effect, and how much of each. When the player crosses into another biome, its
 * share rises steadily to all of it over {@link #SECONDS} and the others' fall away in proportion, so the
 * lighting, sky, fog and colour grading change slowly as they walk. Each biome's settings are worked out at the
 * current time of day and mixed by these shares (LightingState, ColorGrading), so the mix never trails the
 * sun as easing the finished values would. A teleport or a change of world starts over at the new biome.
 */
public final class BiomeMix {
	/** How long a new biome takes to take over entirely. */
	static final double SECONDS = 30.0;

	public record Share(String biome, float weight) {
	}

	private static final Map<String, Float> SHARES = new LinkedHashMap<>();
	private static long lastNanos;
	private static Object lastLevel;

	private BiomeMix() {
	}

	/** The player's biome now, or plains when there's no player. */
	static String playerBiome() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return "minecraft:plains";
		}
		return minecraft.level.getBiome(minecraft.player.blockPosition()).unwrapKey()
				.map(key -> key.identifier().toString()).orElse("minecraft:plains");
	}

	/**
	 * Advances the shares towards the player's biome by the time since the last call (calls in the same frame
	 * barely move them) and returns them, largest first.
	 *
	 * @param snap start over at the player's biome (a teleport)
	 */
	static List<Share> update(boolean snap) {
		String current = playerBiome();
		long now = System.nanoTime();
		double seconds = lastNanos == 0 ? 0 : Math.min((now - lastNanos) / 1e9, 1.0);
		lastNanos = now;
		Object level = Minecraft.getInstance().level;
		if (snap || level != lastLevel || SHARES.isEmpty()) {
			lastLevel = level;
			SHARES.clear();
			SHARES.put(current, 1f);
		} else {
			float before = SHARES.getOrDefault(current, 0f);
			float after = (float) Math.min(1.0, before + seconds / SECONDS);
			// The others give up what the current biome gains, each in proportion to its share.
			float scale = before < 1f ? (1f - after) / (1f - before) : 0f;
			for (Iterator<Map.Entry<String, Float>> it = SHARES.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<String, Float> entry = it.next();
				if (entry.getKey().equals(current)) {
					continue;
				}
				float weight = entry.getValue() * scale;
				if (weight < 0.002f) {
					it.remove();
				} else {
					entry.setValue(weight);
				}
			}
			SHARES.put(current, after);
		}
		return current();
	}

	/** The shares as they stand, largest first, summing to 1. */
	static List<Share> current() {
		List<Share> shares = new ArrayList<>();
		float total = 0;
		for (Map.Entry<String, Float> entry : SHARES.entrySet()) {
			total += entry.getValue();
		}
		for (Map.Entry<String, Float> entry : SHARES.entrySet()) {
			shares.add(new Share(entry.getKey(), total > 0 ? entry.getValue() / total : 1f / SHARES.size()));
		}
		shares.sort((a, b) -> Float.compare(b.weight(), a.weight()));
		if (shares.isEmpty()) {
			shares.add(new Share(playerBiome(), 1f));
		}
		return shares;
	}

	/** A one-line summary for diagnostics. */
	public static String status() {
		StringBuilder text = new StringBuilder();
		for (Share share : current()) {
			if (!text.isEmpty()) {
				text.append(", ");
			}
			text.append(share.biome()).append(' ').append(Math.round(share.weight() * 100)).append('%');
		}
		return text.toString();
	}
}
