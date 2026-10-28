package com.novotimo.mtmixins.mixin.lycanites;

import com.novotimo.mtmixins.bridge.DragonBridge;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B51, second half &mdash; a dying vespid queen no longer clears blocks inside a claim.
 *
 * <p>{@code CreatureStructure.removePitBLocks}, called from the queen's {@code setDead}, sets to air every
 * block within 10 of the hive's origin that matches her dungeon theme's two pit blocks. What those are is
 * the theme's choice, not the town's, so both {@code setBlockToAir} calls (checked with {@code javap}) go
 * through {@link DragonBridge#mobBlockChangeAllowed} like {@link MixinPlaceBlockGoalClaims}. Server only;
 * {@code @Pseudo}, SRG targets with {@code remap = false}; in {@code mixins.mtmixins.lycanitesbuild.json}.
 */
@Pseudo
@Mixin(targets = "com.lycanitesmobs.core.entity.CreatureStructure", remap = false)
public abstract class MixinCreatureStructurePitClaims {

    @Redirect(method = "removePitBLocks",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;func_175698_g(Lnet/minecraft/util/math/BlockPos;)Z"),
            remap = false)
    private boolean mtmixins$gatePitClear(World world, BlockPos pos) {
        if (!DragonBridge.mobBlockChangeAllowed(world, pos, this)) {
            return false;
        }
        return world.setBlockToAir(pos);
    }
}
