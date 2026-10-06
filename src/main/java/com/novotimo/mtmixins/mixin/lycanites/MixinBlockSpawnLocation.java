package com.novotimo.mtmixins.mixin.lycanites;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
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
 * <p>Every spawn location ends up asking the world about candidate positions through
 * {@code World.getBlockState} and {@code World.canSeeSky}, both of which resolve through the generating
 * chunk getter. Scanning for somewhere to put a mob therefore loads, or generates, any chunk the scan
 * happens to reach into. Nothing about spawning requires that; the scan is looking for candidates, not
 * committing to them. There are three routes in, checked against the 1.12.2 source from 2019 through
 * 2.0.8.10:
 *
 * <ul>
 *   <li>{@code RandomSpawnLocation.getRandomYCoord} calls {@code isValidBlock} on bare random columns,
 *       and its override starts with {@code super.isValidBlock}, which lands in
 *       {@code BlockSpawnLocation.isValidBlock}: {@code canSeeSky}, then {@code getBlockState}.</li>
 *   <li>{@code BlockSpawnLocation.getSpawnPositions} sweeps a box around the player and calls
 *       {@code world.getBlockState(pos)} on <i>every</i> candidate to skip flowing liquids,
 *       <b>before</b> it calls {@code isValidBlock}. By the time the guard below runs, that read has
 *       already loaded or generated the chunk, so on this route the guard alone never fired.</li>
 *   <li>{@code MaterialSpawnLocation} inherits that sweep and overrides {@code isValidBlock} without
 *       calling {@code super}, opening with its own {@code getBlockState}. That is covered by
 *       {@link MixinMaterialSpawnLocation}.</li>
 * </ul>
 *
 * <h2>The fix</h2>
 *
 * <p>A position in a chunk that is not loaded is not a valid spawn location, so answer false before
 * anything can generate it, and make the sweep's liquid probe report air for such a position instead of
 * fetching the chunk. Air passes the liquid filters and goes on to {@code isValidBlock}, where the guard
 * rejects it.
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

    /**
     * The sweep's own reads. No ordinal: the first is the per-candidate liquid probe, the second only
     * runs after {@code isValidBlock} said yes, i.e. on a loaded chunk, where this is a pass-through.
     * {@code remap = true} because {@code getBlockState} is vanilla.
     */
    @WrapOperation(
            method = "getSpawnPositions",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState"
                            + "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;",
                    remap = true
            )
    )
    private IBlockState mtmixins$dontGenerateChunksSweepingForSpawns(World world, BlockPos pos,
                                                                     Operation<IBlockState> original) {
        if (!world.isBlockLoaded(pos)) {
            return Blocks.AIR.getDefaultState();
        }
        return original.call(world, pos);
    }
}
