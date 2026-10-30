package dev.bedrockvisuals.compat;

import dev.bedrockvisuals.BedrockVisualsClient;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/**
 * Knows when a video exporter is running, through FREX's flawless frames convention: exporters (Flashback,
 * Replay Mod) declare a "frex_flawless_frames" entrypoint, renderers hand each one an activator factory,
 * and the exporter switches its activator on for the length of an export. No mixins, so exporter updates
 * can't break it.
 *
 * <p>While {@link #isExporting()} is true the renderer uses the offline preset: no TAA, sub-frame
 * accumulation, and every frame complete.
 */
public final class FlawlessFrames {
	private static final String ENTRYPOINT = "frex_flawless_frames";
	private static final Set<String> ACTIVE = ConcurrentHashMap.newKeySet();

	private FlawlessFrames() {
	}

	public static boolean isExporting() {
		return !ACTIVE.isEmpty();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public static void register() {
		Function<String, Consumer<Boolean>> activatorFactory = exporter -> active -> {
			if (active ? ACTIVE.add(exporter) : ACTIVE.remove(exporter)) {
				BedrockVisualsClient.LOGGER.info("[spike 1c] {} export {}", exporter, active ? "started" : "finished");
			}
		};
		for (EntrypointContainer<Consumer> container
				: FabricLoader.getInstance().getEntrypointContainers(ENTRYPOINT, Consumer.class)) {
			container.getEntrypoint().accept(activatorFactory);
			BedrockVisualsClient.LOGGER.info("[spike 1c] listening for exports from {}",
					container.getProvider().getMetadata().getId());
		}
	}
}
