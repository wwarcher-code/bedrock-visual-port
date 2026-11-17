package dev.bedrockvisuals.mixin.minecraft;

import dev.bedrockvisuals.render.ChunkChanges;
import dev.bedrockvisuals.render.WaterColumns;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the water column map (WaterColumns) and the light volume (ChunkChanges) which chunks changed: every
 * way Minecraft marks blocks or sections for a rebuild arrives here, light updates included (ClientChunkCache's
 * onLightUpdate calls setSectionDirty directly, so hooks on ClientLevel missed them). Sodium overwrites these
 * methods; a priority above the default applies these injections after its overwrites, into Sodium's bodies.
 */
@Mixin(value = LevelExtractor.class, priority = 1100)
abstract class LevelExtractorMixin {
	private static void bedrockvisuals$changed(int chunkX, int chunkZ) {
		WaterColumns.markDirty(chunkX, chunkZ);
		ChunkChanges.mark(chunkX, chunkZ);
	}

	@Inject(method = "blockChanged", at = @At("HEAD"))
	private void bedrockvisuals$blockUpdated(BlockPos pos, int flags, CallbackInfo ci) {
		bedrockvisuals$changed(pos.getX() >> 4, pos.getZ() >> 4);
	}

	@Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"))
	private void bedrockvisuals$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		bedrockvisuals$changed(pos.getX() >> 4, pos.getZ() >> 4);
	}

	@Inject(method = "setBlocksDirty", at = @At("HEAD"))
	private void bedrockvisuals$blocksChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
			CallbackInfo ci) {
		// Block coordinates (setBlocksDirty marks the sections around a box of blocks, a block wider each way).
		for (int z = (minZ - 1) >> 4; z <= (maxZ + 1) >> 4 && z <= ((minZ - 1) >> 4) + 32; z++) {
			for (int x = (minX - 1) >> 4; x <= (maxX + 1) >> 4 && x <= ((minX - 1) >> 4) + 32; x++) {
				bedrockvisuals$changed(x, z);
			}
		}
	}

	@Inject(method = "setSectionDirty(III)V", at = @At("HEAD"))
	private void bedrockvisuals$sectionChanged(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
		bedrockvisuals$changed(sectionX, sectionZ);
	}

	@Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
	private void bedrockvisuals$sectionAndNeighborsChanged(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
		for (int z = sectionZ - 1; z <= sectionZ + 1; z++) {
			for (int x = sectionX - 1; x <= sectionX + 1; x++) {
				bedrockvisuals$changed(x, z);
			}
		}
	}

	@Inject(method = "setSectionRangeDirty", at = @At("HEAD"))
	private void bedrockvisuals$sectionsChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
			CallbackInfo ci) {
		// Section coordinates; a chunk's worth at most in practice (a light update's reach), bounded anyway.
		for (int z = minZ; z <= maxZ && z <= minZ + 32; z++) {
			for (int x = minX; x <= maxX && x <= minX + 32; x++) {
				bedrockvisuals$changed(x, z);
			}
		}
	}
}
