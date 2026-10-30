package dev.bedrockvisuals.mixin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/** Applies each hooked mod's mixins only when that mod is loaded; the subpackage names the mod. */
public class BedrockVisualsMixinPlugin implements IMixinConfigPlugin {
	private static final Map<String, String> MOD_BY_PACKAGE = Map.of(
			".sodium.", "sodium",
			".replaymod.", "replaymod");

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		for (Map.Entry<String, String> entry : MOD_BY_PACKAGE.entrySet()) {
			if (mixinClassName.contains(entry.getKey())) {
				return FabricLoader.getInstance().isModLoaded(entry.getValue());
			}
		}
		return true;
	}

	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
