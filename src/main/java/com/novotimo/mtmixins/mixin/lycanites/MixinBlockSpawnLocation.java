package com.novotimo.mtmixins.mixin.lycanites;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B13 &mdash; Lycanites' spawn-location check generates chunks to decide whether a mob may
 * spawn in them. After B12 and the AncientWarfare config change this became the single largest cause of
 * chunk generation on the server, at 17.1% of the server thread and 196 of 294 cascading worldgen
 * events in one four-minute window.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code BlockSpawnLocation.getSpawnPositions} sweeps a region around the player with a
 * {@code MutableBlockPos} and calls {@code isValidBlock(world, pos)} on each candidate.
 * {@code isValidBlock} then does, in order:
 *
 * <pre>
 * world.canSeeSky(pos)        // func_175678_i - resolves through getChunk, so it generates
 * world.getBlockState(pos)    // func_180495_p - likewise
 * </pre>
 *
 * <p>Both go through the generating chunk getter, so scanning for somewhere to put a mob generates any
 * chunk the scan happens to reach into. Nothing about spawning requires that; the scan is looking for
 * candidates, not committing to them.
 *
 * <h2>The fix</h2>
 *
 * <p>A position in a chunk that is not loaded is not a valid spawn location, so answer false before
 * either call can generate anything.
 *
 * <p><b>This one has no trade-off</b>, which is worth saying explicitly given B12's did. A structure
 * that fails validation is not placed and never reconsidered, so refusing to generate costs real
 * structures. Spawning is the opposite: it runs continuously, over a region that moves with the player,
 * and any candidate rejected now is reconsidered as soon as the chunk is loaded for an ordinary reason.
 * Mobs should not be spawning into chunks nobody has loaded in the first place &mdash; the loaded
 * region is where players are, which is the only place a spawn can matter.
 *
 * <p>{@code World.isBlockLoaded} resolves through {@code ChunkProviderServer.getLoadedChunk}, which
 * returns null for an absent chunk rather than generating one, so the check cannot itself trigger what
 * it is meant to prevent.
 */
@Pseudo
@Mixin(targets = "com.lycanitesmobs.core.spawner.location.BlockSpawnLocation", remap = false)
public abstract class MixinBlockSpawnLocation {

    @Inject(method = "isValidBlock", at = @At("HEAD"), cancellable = true)
    private void mtmixins$dontGenerateChunksLookingForSpawns(World world, BlockPos pos,
                                                             CallbackInfoReturnable<Boolean> cir) {
        if (!world.isBlockLoaded(pos)) {
            cir.setReturnValue(false);
        }
    }
}
