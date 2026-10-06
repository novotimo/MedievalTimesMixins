package com.novotimo.mtmixins.mixin.lycanites;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B13, the {@code MaterialSpawnLocation} half. See {@link MixinBlockSpawnLocation} for the
 * write-up.
 *
 * <p>{@code MaterialSpawnLocation} (water, lava and other material-based spawners) overrides
 * {@code isValidBlock} and does not call {@code super}; its first statement is
 * {@code world.getBlockState(blockPos)}. The guard on {@code BlockSpawnLocation.isValidBlock} is
 * therefore never reached for it, and its inherited sweep would hand it unloaded positions to load.
 * Same guard, same reasoning: an unloaded position is not a valid spawn location.
 */
@Pseudo
@Mixin(targets = "com.lycanitesmobs.core.spawner.location.MaterialSpawnLocation", remap = false)
public abstract class MixinMaterialSpawnLocation {

    @Inject(method = "isValidBlock", at = @At("HEAD"), cancellable = true)
    private void mtmixins$dontGenerateChunksLookingForSpawns(World world, BlockPos pos,
                                                             CallbackInfoReturnable<Boolean> cir) {
        if (!world.isBlockLoaded(pos)) {
            cir.setReturnValue(false);
        }
    }
}
