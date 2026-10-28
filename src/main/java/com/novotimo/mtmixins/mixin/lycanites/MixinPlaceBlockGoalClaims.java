package com.novotimo.mtmixins.mixin.lycanites;

import com.novotimo.mtmixins.bridge.DragonBridge;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B51 &mdash; Lycanites creatures no longer build in, or dig out, claims (with
 * {@link MixinCreatureStructurePitClaims}).
 *
 * <h2>The fault</h2>
 *
 * <p>Vespids build their hive through {@code PlaceBlockGoal}, which the vespid configures with
 * {@code setReplaceSolid(true)} and {@code setReplaceLiquid(true)}: on reaching its target it calls
 * {@code World.setBlockState(pos, state, 3)} over whatever is there. Lycanites fires no mob-griefing
 * event for it, and its per-creature {@code griefing} flag does not cover it, so wasps replaced town
 * walls with hive blocks (triage B-07).
 *
 * <h2>The fix</h2>
 *
 * <p>The single {@code setBlockState} in {@code updateTask} (checked with {@code javap}) asks
 * {@link DragonBridge#mobBlockChangeAllowed} first, the policy Ice and Fire's griefers go through: refused in
 * a claim unless the town opted in with {@code /town toggle mobs}, allowed during a raid on that town once
 * journalled. A refused placement still runs the goal's tail, so the build task is marked done and the
 * creature moves on; the queen's periodic {@code refreshBuildTasks} hands the spot out again later, so
 * hives near a town simply stay unfinished on the claimed side.
 *
 * <p>Server only. Lycanites is compiled against SRG and not in {@code libs/}, so the class is named by
 * string with {@code @Pseudo} and the targets are SRG with {@code remap = false}. Lives in
 * {@code mixins.mtmixins.lycanitesbuild.json}, so it can be switched off without a rebuild.
 */
@Pseudo
@Mixin(targets = "com.lycanitesmobs.core.entity.goals.actions.abilities.PlaceBlockGoal", remap = false)
public abstract class MixinPlaceBlockGoalClaims {

    @Redirect(method = "func_75246_d",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;func_180501_a(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;I)Z"),
            remap = false)
    private boolean mtmixins$gatePlacement(World world, BlockPos pos, IBlockState state, int flags) {
        if (!DragonBridge.mobBlockChangeAllowed(world, pos, this)) {
            return false;
        }
        return world.setBlockState(pos, state, flags);
    }
}
