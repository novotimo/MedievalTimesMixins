package com.novotimo.mtmixins.mixin.minefantasy;

import minefantasy.mfr.container.ContainerCrucible;
import minefantasy.mfr.tile.TileEntityCrucible;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reaches the crucible behind an open crucible window from register B43, which watches slot clicks in that
 * window to see who loads the crucible. {@code ContainerCrucible.tile} is private with no getter.
 *
 * <p>MFR is not obfuscated, so {@code remap = false}; the name is the one in the shipped jar.
 */
@Mixin(value = ContainerCrucible.class, remap = false)
public interface AccessorContainerCrucible {

    @Accessor(value = "tile", remap = false)
    TileEntityCrucible mtmixins$tile();
}
