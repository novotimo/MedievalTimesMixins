package com.novotimo.mtmixins.mixin.minefantasy;

import java.util.Set;
import minefantasy.mfr.registry.recipe.CrucibleCraftMatrix;
import minefantasy.mfr.tile.TileEntityCrucible;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches two private fields of MFR's crucible from register B43 ({@code util/CrucibleResearch}): the alloy
 * research list it smelts with, which MFR only ever sets, and the 3x3 matrix its {@code getResult} fills
 * from the inputs every tick, which B43 fills the same way to see which alloy is loaded.
 *
 * <p>MFR is not obfuscated, so {@code remap = false}; the names are the ones in the shipped jar.
 */
@Mixin(value = TileEntityCrucible.class, remap = false)
public interface AccessorTileEntityCrucible {

    @Accessor(value = "knownResearches", remap = false)
    Set<String> mtmixins$knownResearches();

    @Accessor(value = "craftMatrix", remap = false)
    CrucibleCraftMatrix mtmixins$craftMatrix();
}
