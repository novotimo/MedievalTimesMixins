package com.novotimo.mtmixins.mixin.sereneseasons;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B45, second half &mdash; Serene Tweaks' snow recalculation no longer loads the chunks next to the
 * ones it works on.
 *
 * <p>When a chunk loads that nobody has been in for {@code timeToRecalculateSnow} minutes &mdash; after a
 * restart, every chunk, because its timestamps are never marked dirty and so never saved &mdash;
 * {@code SnowRecalculationHandler.onTick} freezes water, adds snow and melts both across all 256 columns with
 * {@code setBlockState(pos, state, 2)}. Flag 2 still runs vanilla's observer check, which reads the six
 * neighbouring blocks; at a chunk edge one of those is in the next chunk, and if that is not loaded,
 * {@code getBlockState} loads it from disk on the server thread. That chunk is then queued for the same
 * recalculation. On prod's 2026-10-28 profile this was 0.64% of the server thread in synchronous chunk loads,
 * the largest single source of them.
 *
 * <p>The four {@code setBlockState} calls now pass flag 16 as well, "do not notify observers", which is what
 * vanilla's own world generation uses. Clients still get the change (flag 2); no neighbour updates were sent
 * before either. The only difference: an observer block facing snow or ice that Serene Tweaks changes in an
 * unvisited chunk does not pulse.
 *
 * <p>{@code @Pseudo} and {@code remap = false}: Serene Tweaks is not obfuscated and may be absent. The target
 * {@code World.func_180501_a} was read from {@code SereneTweaks-1.0.jar} with {@code javap}. Server only, in
 * {@code mixins.mtmixins.serenetweaks.json}.
 */
@Pseudo
@Mixin(targets = "serenetweaks.handlers.SnowRecalculationHandler", remap = false)
public abstract class MixinSnowRecalculationNoObservers {

    @Redirect(method = "onTick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;func_180501_a(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z"),
            remap = false, require = 4, allow = 4)
    private boolean mtmixins$setWithoutObservers(World world, BlockPos pos, IBlockState state, int flags) {
        return world.setBlockState(pos, state, flags | 16);
    }
}
