package dev.bedrockvisuals.mixin.minecraft;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A sprite's pixels as loaded (every frame of an animated one, stacked), for raytrace/EmitterColors. */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
	@Accessor("originalImage")
	NativeImage bedrockvisuals$originalImage();
}
