package com.novotimo.mtmixins.mixin.ancientwarfare;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.World;
import net.shadowmage.ancientwarfare.structure.town.TownTemplate;
import net.shadowmage.ancientwarfare.structure.town.WorldTownGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Register B47 &mdash; Ancient Warfare no longer generates hundreds of chunks out of sight to size a town it
 * then cuts down.
 *
 * <h2>The cost</h2>
 *
 * <p>For every new chunk that rolls {@code town_generation_chance} with no town within
 * {@code town_min_distance}, {@code WorldTownGenerator.attemptGeneration} asks
 * {@code TownPlacementValidator.findGenerationPosition} for an area. That grows a box outward one chunk row at
 * a time while the ground stays level enough, testing the average height of every chunk it grows into, up
 * to {@code maxSize} = 32, i.e. 33 &times; 33 chunks. Each tested chunk that does not exist yet is generated
 * on the spot, terrain and caves, on the server thread. Prod's 2026-10-28 profile of a player flying a dragon
 * over new terrain: 32.5% of the server thread in Ancient Warfare's world-gen tick, 16.2% in this search
 * alone, nearly all of it generating chunks.
 *
 * <p>The area is then cut to the chosen template's {@code maxsize + 1} chunks a side
 * ({@code selectTemplateAndShrinkToMax}). This pack's ten town templates have {@code maxsize} 8 to 20, so
 * most of a 33-chunk search is generated, measured and thrown away.
 *
 * <h2>The fix</h2>
 *
 * <p>Around that one call, {@code maxSize} is set to the largest {@code maxsize} among the templates valid at
 * that spot (the list {@code attemptGeneration} already built), then put back. The search can then still
 * reach {@code maxsize + 1}, exactly what the shrink would keep. Which templates can be chosen is unchanged:
 * {@code selectTemplateFittingArea} accepts a template when {@code min(width, length) >= minsize}, and every
 * {@code minsize} is at most its own {@code maxsize}. Worst case per attempt: 441 chunks where Empire's
 * walled city can spawn, 81 where only graveyards can, instead of 1,089.
 *
 * <p>In game: towns still appear wherever they did and with the same templates; one may sit a few chunks
 * from where the larger search would have anchored it, on ground the same checks approved. Fewer chunks get
 * generated out of everyone's sight.
 *
 * <p>{@code remap = false}: Ancient Warfare is not obfuscated; the call, and the template list in local 4,
 * were checked with {@code javap} against ancientwarfare-1.12.2-2.9.20.jar. Server only. Its own config file,
 * {@code mixins.mtmixins.awtowns.json}, so it can be switched off in {@code config/mixinbooter.cfg} without a
 * rebuild.
 */
@Mixin(value = WorldTownGenerator.class, remap = false)
public abstract class MixinWorldTownGeneratorSearchCap {

    @WrapOperation(method = "attemptGeneration",
            at = @At(value = "INVOKE",
                    target = "Lnet/shadowmage/ancientwarfare/structure/town/TownPlacementValidator;findGenerationPosition(Lnet/minecraft/world/World;II)Ljava/util/Optional;"),
            remap = false)
    private Optional<?> mtmixins$searchNoFurtherThanTemplates(World world, int x, int z, Operation<Optional<?>> original,
            @Local(index = 4) List<TownTemplate> templates) {
        int largest = 0;
        for (TownTemplate template : templates) {
            largest = Math.max(largest, template.getMaxSize());
        }

        int vanilla = AccessorTownPlacementValidator.mtmixins$getMaxSize();
        if (largest <= 0 || largest >= vanilla) {
            return original.call(world, x, z);
        }

        AccessorTownPlacementValidator.mtmixins$setMaxSize(largest);
        try {
            return original.call(world, x, z);
        } finally {
            AccessorTownPlacementValidator.mtmixins$setMaxSize(vanilla);
        }
    }
}
