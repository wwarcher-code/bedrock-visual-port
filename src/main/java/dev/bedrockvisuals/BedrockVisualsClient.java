package dev.bedrockvisuals;

import dev.bedrockvisuals.bedrock.BedrockInputs;
import dev.bedrockvisuals.compat.DistantHorizonsAdapter;
import dev.bedrockvisuals.compat.FlawlessFrames;
import dev.bedrockvisuals.compat.PinnedVersions;
import dev.bedrockvisuals.render.GBufferDebugView;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BedrockVisualsClient implements ClientModInitializer {
	public static final String MOD_ID = "bedrockvisuals";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		PinnedVersions.check();
		BedrockInputs.load(FabricLoader.getInstance().getConfigDir());
		if (FabricLoader.getInstance().isModLoaded("distanthorizons")) {
			DistantHorizonsAdapter.register();
		}
		FlawlessFrames.register();
		GBufferDebugView.register();
	}
}
