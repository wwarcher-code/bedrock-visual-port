package dev.bedrockvisuals.mixin.renderpearl;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.bedrockvisuals.raytrace.RayQuery;
import java.util.LinkedHashSet;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds hardware ray queries (RayQuery.FEATURE_SET) to the device's optional feature sets: renderpearl enables
 * them when the GPU supports them and creates the device without them otherwise, so nothing changes on a card
 * without ray tracing.
 */
@Mixin(VulkanFeatureSets.class)
abstract class VulkanFeatureSetsMixin {
	@Inject(method = "optionalFeatureSets", at = @At("RETURN"), cancellable = true)
	private static void bedrockvisuals$rayQuery(CallbackInfoReturnable<Set<FeatureSet>> cir) {
		Set<FeatureSet> sets = new LinkedHashSet<>(cir.getReturnValue());
		sets.add(RayQuery.featureSet());
		cir.setReturnValue(sets);
	}
}
