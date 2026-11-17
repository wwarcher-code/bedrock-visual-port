package dev.bedrockvisuals.mixin.minecraft;

import dev.bedrockvisuals.render.WaterColumns;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the water column map (WaterColumns) which chunks changed: where Minecraft marks blocks or sections for a
 * rebuild (a block placed or broken, light that moved), the chunk is mapped again. Without this the map found
 * changes only by sweeping every column, which cost the most frames over the sea.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "sendBlockUpdated", at = @At("HEAD"))
	private void bedrockvisuals$blockChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags,
			CallbackInfo ci) {
		WaterColumns.markDirty(pos.getX() >> 4, pos.getZ() >> 4);
	}

	@Inject(method = "setSectionDirtyWithNeighbors", at = @At("HEAD"))
	private void bedrockvisuals$sectionChanged(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
		WaterColumns.markDirty(sectionX, sectionZ);
	}

	@Inject(method = "setSectionRangeDirty", at = @At("HEAD"))
	private void bedrockvisuals$sectionsChanged(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
			CallbackInfo ci) {
		// Section coordinates; a chunk's worth at most in practice (a light update's reach), bounded anyway.
		for (int z = minZ; z <= maxZ && z <= minZ + 32; z++) {
			for (int x = minX; x <= maxX && x <= minX + 32; x++) {
				WaterColumns.markDirty(x, z);
			}
		}
	}
}
