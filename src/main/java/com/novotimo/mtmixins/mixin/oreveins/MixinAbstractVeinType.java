package com.novotimo.mtmixins.mixin.oreveins;

import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Register B2 &mdash; replaces the hand-edited OreVeins jar, but with a real fix rather than
 * the deletion that jar shipped.
 *
 * <p>The edited jar removed this loop from {@code matchesBiome}:
 *
 * <pre>
 * for (BiomeDictionary.Type type : BiomeDictionary.getTypes(biome))
 *     if (name.equalsIgnoreCase(type.getName()))
 *         return biomesIsWhitelist;
 * </pre>
 *
 * which silently broke every vein definition that selects biomes by BiomeDictionary type
 * (<code>FOREST</code>, <code>MOUNTAIN</code>, …) rather than by exact biome name. This mixin
 * keeps the loop and removes the cost instead.
 *
 * <p><b>Why cache {@code matchesBiome} and not {@code getTypes}.</b>
 * {@code WorldGenVeins.generate} walks all 256 columns of the chunk and calls
 * {@code world.getBiome(...)} then {@code matchesBiome(...)} for <em>each</em> one, per nearby
 * vein:
 *
 * <pre>
 * for (int x = chunkX; x &lt; chunkX + 16; x++)
 *     for (int z = chunkZ; z &lt; chunkZ + 16; z++) {
 *         Biome biome = world.getBiome(new BlockPos(x, 0, z));
 *         if (vein.getType().matchesBiome(biome)) { ... }
 *     }
 * </pre>
 *
 * Each {@code matchesBiome} call iterates the vein's configured biome list, and for every entry
 * builds a biome name and consults {@code BiomeDictionary}. Caching {@code getTypes} alone would
 * barely help, because {@code BiomeDictionary.getTypes} is only
 * {@code ensureHasTypes(biome); return getBiomeInfo(biome).typeList;} &mdash; two map lookups and
 * no allocation. Caching the <em>answer</em> collapses all 256 calls to one per distinct biome in
 * the chunk, typically one to four, and takes the list iteration and string comparisons with it.
 *
 * <p>The result depends only on the biome and on this instance's {@code biomes} /
 * {@code biomesIsWhitelist}, both deserialised from config once at load, so the cache never needs
 * invalidating. A config reload constructs new vein type instances, each with a fresh cache.
 *
 * <p>Not fixed here: the 256 {@code world.getBiome} calls themselves. Those are 256 distinct
 * positions, so there is nothing to cache &mdash; every call is a genuine lookup. Removing them
 * would mean sampling the biome less often than per column, which changes where veins generate.
 * That is a behaviour decision for upstream, not something to do behind a mixin.
 */
@Pseudo
@Mixin(targets = "com.alcatrazescapee.oreveins.api.AbstractVeinType", remap = false)
public abstract class MixinAbstractVeinType {

    /**
     * Per-instance because the answer depends on this vein type's own biome list. Concurrent
     * because worldgen is not guaranteed to stay on one thread, and the cost of a
     * {@link ConcurrentHashMap} read is trivial next to the work it replaces.
     */
    @Unique
    private final Map<Biome, Boolean> mtmixins$biomeMatchCache = new ConcurrentHashMap<>();

    @Inject(method = "matchesBiome", at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$matchesBiomeFromCache(Biome biome, CallbackInfoReturnable<Boolean> cir) {
        if (biome == null) {
            return;
        }
        Boolean cached = this.mtmixins$biomeMatchCache.get(biome);
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    /**
     * Only reached when the HEAD injector did not already return, so this stores exactly the
     * results computed by the real method body.
     */
    @Inject(method = "matchesBiome", at = @At("RETURN"), remap = false)
    private void mtmixins$cacheMatchesBiome(Biome biome, CallbackInfoReturnable<Boolean> cir) {
        if (biome != null) {
            this.mtmixins$biomeMatchCache.put(biome, cir.getReturnValue());
        }
    }
}
