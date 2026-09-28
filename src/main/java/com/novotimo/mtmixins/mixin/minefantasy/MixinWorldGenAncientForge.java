package com.novotimo.mtmixins.mixin.minefantasy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Register B12 &mdash; MineFantasy's Ancient Forge validator generates chunks while deciding whether it
 * can build, which is the mod's actual cascading-worldgen source.
 *
 * <h2>How this was found</h2>
 *
 * <p>Not by reading the world generators. Two earlier guesses from source were both wrong: the ore
 * generator turned out to match vanilla exactly (see the README note on where the populate offset
 * lives), and the plant generator's spread bottoms out at +1 so it never leaves the chunk. What
 * identified it was a spark profile, walking every stack that reached a chunk-generation call and
 * blaming the nearest non-vanilla frame above it:
 *
 * <pre>
 * 43148 ms  AncientWarfare  TownPlacementValidator.isAverageHeightWithin
 * 41600 ms  MineFantasy     WorldGenAncientForge.canStructureBuild
 * 12080 ms  AncientWarfare  WorldStructureGenerator.getTargetY
 * </pre>
 *
 * <p>That also matches the direction of the warnings. Of 203 MineFantasy cascades in one session, 178
 * were into {@code -1,-1}, {@code 0,-1} or {@code -1,0}. During {@code populate(x, z)} only
 * {@code (x,z)} through {@code (x+1,z+1)} is guaranteed loaded, so the negative quadrant is never safe
 * &mdash; and a validator sampling a neighbourhood around its own origin walks straight into it, while
 * a decorator placing features forward does not. Quark and RTG, by contrast, cascade almost entirely
 * into {@code +1}.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code canStructureBuild} sweeps {@code i} in {@code -1..1}, {@code j} in {@code 0..2} and
 * {@code k} in {@code -1..2}, calling {@code world.getBlockState(module.offsetPos(i, j, k, facing))}
 * to check there is solid ground below and clear space above. Each of those reads on a chunk that is
 * not loaded makes Forge generate it mid-population, and that chunk's own population can pull in more.
 *
 * <h2>The fix</h2>
 *
 * <p>Refuse to generate a chunk in order to answer a question about whether to build. If the position
 * is not already loaded, return air. Air fails the "solid ground here" half of the check, so
 * {@code canStructureBuild} returns false and the forge simply is not placed at that spot.
 *
 * <p>{@code World.isBlockLoaded} is the right test because it resolves through
 * {@code ChunkProviderServer.getLoadedChunk}, which returns null for an absent chunk rather than
 * generating one &mdash; asking the question cannot itself cause the thing we are trying to prevent.
 *
 * <p><b>The trade-off, stated plainly:</b> this can make an Ancient Forge fail to place where it would
 * previously have generated a chunk to check. It only bites when validation reaches outside the loaded
 * 2x2, and since the structure is anchored near the populating chunk most of its sweep is inside that
 * area, so the expected reduction is small &mdash; but it is not zero, and it is not something I can
 * establish by reading. A/B it: compare the MineFantasy cascade count, and confirm forges still
 * generate, before trusting it.
 *
 * <p>Ancient Forges are gated by {@code confineToGrid(chunkX, chunkZ, ancientForgeGrid)} and a spawn
 * chance, so they are rare to begin with; the cost is concentrated in how expensive each attempt is,
 * not how often one happens.
 */
@Pseudo
@Mixin(targets = "minefantasy.mfr.world.gen.structure.WorldGenAncientForge", remap = false)
public abstract class MixinWorldGenAncientForge {

    /**
     * {@code remap = true} on the {@code @At} target: {@code getBlockState} is vanilla and needs the
     * refmap, even though the enclosing mixin is {@code remap = false} for MineFantasy's own names.
     * No ordinal, so every read in the method is covered.
     */
    @WrapOperation(
            method = "canStructureBuild",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBlockState"
                            + "(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/state/IBlockState;",
                    remap = true
            )
    )
    private IBlockState mtmixins$dontGenerateChunksToValidate(World world, BlockPos pos,
                                                              Operation<IBlockState> original) {
        if (!world.isBlockLoaded(pos)) {
            mtmixins$countRefusal();
            return Blocks.AIR.getDefaultState();
        }
        return original.call(world, pos);
    }

    /**
     * Makes the cost of this fix visible instead of assumed. Each refusal is one validation read that
     * would previously have generated a chunk. It is an upper bound on suppressed forges rather than a
     * count of them: a refusal during the "solid ground below" sweep fails the check immediately, but
     * one during the "clear space above" sweep is satisfied by air and the structure still places.
     *
     * <p>If this number stays small over a long generation run, the fix is close to free. If it is
     * large, Ancient Forges are being skipped often enough to be worth a different approach - most
     * likely deferring placement to a later tick, once the chunks it wants to inspect exist anyway.
     */
    @Unique
    private static void mtmixins$countRefusal() {
        long n = ++mtmixins$refusals;
        if (n == 1L || n % 200L == 0L) {
            MedievalTimesMixins.LOG.info(
                    "Refused to generate a chunk for MineFantasy Ancient Forge validation ({} so far). "
                            + "Each refusal is a cascading worldgen event avoided; see B12.", n);
        }
    }

    @Unique
    private static long mtmixins$refusals = 0L;
}
