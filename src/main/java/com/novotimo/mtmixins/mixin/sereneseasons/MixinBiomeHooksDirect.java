package com.novotimo.mtmixins.mixin.sereneseasons;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import sereneseasons.api.season.BiomeHooks;
import sereneseasons.api.season.Season;
import sereneseasons.season.SeasonASMHelper;

/**
 * Register B45, first half &mdash; Serene Seasons' temperature API no longer looks itself up by reflection on
 * every call.
 *
 * <p>{@code sereneseasons.api.season.BiomeHooks} (SereneSeasons 1.2.18) answers both
 * {@code getFloatTemperature} overloads with
 * {@code Class.forName("sereneseasons.season.SeasonASMHelper").getMethod(...).invoke(...)}, every time. Serene
 * Tweaks calls it for every column of every chunk it recalculates snow in, so on prod's 2026-10-28 profile (a
 * player flying a dragon over new terrain) {@code Class.forName} alone was 1.5% of the server thread. Both
 * overloads now call {@code SeasonASMHelper} directly, which is what the reflection resolved to: same method,
 * same arguments, same result.
 *
 * <p>Server only, in {@code mixins.mtmixins.serenetweaks.json} with the second half,
 * {@link MixinSnowRecalculationNoObservers}.
 */
@Mixin(value = BiomeHooks.class, remap = false)
public abstract class MixinBiomeHooksDirect {

    /**
     * @author mtmixins (register B45)
     * @reason Called per block column; the original resolves the target by reflection on every call.
     */
    @Overwrite(remap = false)
    public static float getFloatTemperature(World world, Biome biome, BlockPos pos) {
        return SeasonASMHelper.getFloatTemperature(world, biome, pos);
    }

    /**
     * @author mtmixins (register B45)
     * @reason Called per block column; the original resolves the target by reflection on every call.
     */
    @Overwrite(remap = false)
    public static float getFloatTemperature(Season.SubSeason subSeason, Biome biome, BlockPos pos) {
        return SeasonASMHelper.getFloatTemperature(subSeason, biome, pos);
    }
}
