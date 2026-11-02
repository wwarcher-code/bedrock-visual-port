package dev.bedrockvisuals.mixin.distanthorizons;

import com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper;
import dev.bedrockvisuals.BedrockVisualsClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps Java's Clouds option. With its "override vanilla graphics settings" on (the default), Distant
 * Horizons sets Java's clouds to OFF each time its renderer starts, so they don't draw over its distant
 * terrain. This renderer draws Bedrock-lit clouds on Java's cloud mesh and hides Distant Horizons' own, so
 * that would leave no clouds at all.
 */
@Mixin(value = MinecraftClientWrapper.class, remap = false)
abstract class MinecraftClientWrapperMixin {
	@Inject(method = "disableVanillaClouds", at = @At("HEAD"), cancellable = true)
	private void bedrockvisuals$keepClouds(CallbackInfo ci) {
		BedrockVisualsClient.LOGGER.info("[clouds] kept Java's Clouds setting (Distant Horizons would turn it off)");
		ci.cancel();
	}
}
