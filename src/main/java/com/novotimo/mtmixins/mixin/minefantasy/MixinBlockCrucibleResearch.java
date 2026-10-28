package com.novotimo.mtmixins.mixin.minefantasy;

import com.novotimo.mtmixins.util.CrucibleResearch;
import java.util.Set;
import minefantasy.mfr.block.BlockCrucible;
import minefantasy.mfr.tile.TileEntityCrucible;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B43 &mdash; a MineFantasy crucible no longer takes its research from whoever last clicked it,
 * and a player cannot open one that is smelting an alloy they have not researched.
 *
 * <h2>The fault</h2>
 *
 * <p>{@code TileEntityCrucible.getResult} smelts an alloy only if its research is in the crucible's
 * {@code knownResearches}. {@code BlockCrucible.onBlockActivated} rebuilt that list from the clicking
 * player's research on every right-click, before deciding whether the click was a mould, the Trilogy
 * jewel or opening the window. So a player with bronze but not black steel who opened a town smithy, or
 * just took bars out with a mould, stopped the black steel batch in it until a researched player clicked
 * it again. Players reported it as crucibles intermittently "losing" alloys.
 *
 * <h2>The fix</h2>
 *
 * <p>Two redirects in {@code onBlockActivated}, each on a call made exactly once there (checked with
 * {@code javap}): {@code setKnownResearches} no longer happens on a click; the list is set by
 * {@code mixin/vanilla/MixinNetHandlerPlayServerCrucibleLoad} when a player loads the inputs.
 * {@code openGUI} asks {@link CrucibleResearch#mayOpen} first. The rules are in {@link CrucibleResearch}.
 * MFR's own check that the player knows bronze smelting, ahead of both, is unchanged.
 *
 * <p>Server only: the window is opened by the server, and the list only matters there. The client's copy
 * of the crucible still sets its own list on a click, which only affects the client's local prediction.
 * MFR is not obfuscated, so the method is named in SRG with {@code remap = false}. Lives in
 * {@code mixins.mtmixins.crucible.json}, so it can be switched off in {@code config/mixinbooter.cfg}
 * without a rebuild.
 */
@Mixin(value = BlockCrucible.class, remap = false)
public abstract class MixinBlockCrucibleResearch {

    @Redirect(method = "func_180639_a",
            at = @At(value = "INVOKE",
                    target = "Lminefantasy/mfr/tile/TileEntityCrucible;setKnownResearches(Ljava/util/Set;)V"),
            remap = false)
    private void mtmixins$keepResearchOnClick(TileEntityCrucible crucible, Set<String> clickerResearch) {
        // Deliberately nothing: a click is not a load.
    }

    @Redirect(method = "func_180639_a",
            at = @At(value = "INVOKE",
                    target = "Lminefantasy/mfr/tile/TileEntityCrucible;openGUI(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;)V"),
            remap = false)
    private void mtmixins$openIfResearched(TileEntityCrucible crucible, World world, EntityPlayer player) {
        if (CrucibleResearch.mayOpen(crucible, player)) {
            crucible.openGUI(world, player);
        }
    }
}
