package com.novotimo.mtmixins.mixin.ancientwarfare;

import net.shadowmage.ancientwarfare.structure.town.TownPlacementValidator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Register B47 &mdash; reaches {@code TownPlacementValidator.maxSize}, the private static limit on how far
 * Ancient Warfare grows a candidate town area. See {@link MixinWorldTownGeneratorSearchCap}.
 */
@Mixin(value = TownPlacementValidator.class, remap = false)
public interface AccessorTownPlacementValidator {

    @Accessor(value = "maxSize", remap = false)
    static int mtmixins$getMaxSize() {
        throw new AssertionError();
    }

    @Accessor(value = "maxSize", remap = false)
    static void mtmixins$setMaxSize(int maxSize) {
        throw new AssertionError();
    }
}
