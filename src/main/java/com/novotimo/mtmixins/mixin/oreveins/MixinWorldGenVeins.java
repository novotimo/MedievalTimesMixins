package com.novotimo.mtmixins.mixin.oreveins;

import com.alcatrazescapee.oreveins.api.IVein;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.novotimo.mtmixins.util.ChunkBiomeCache;
import net.minecraft.init.Biomes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Random;

/**
 * Register B2b &mdash; OreVeins worldgen cost. Three layers, in order of how much they save.
 *
 * <p>Source: {@code alcatrazEscapee/ore-veins}, branch {@code 1.12}, MIT. The mod is a
 * {@code compileOnly} dependency resolved through CurseMaven, so nothing is redistributed.
 *
 * <h2>The scale problem</h2>
 *
 * <p>{@code getNearbyVeins} scans {@code (2 * CHUNK_RADIUS + 1)^2} chunks for every chunk generated,
 * and {@code CHUNK_RADIUS} is {@code 4 + max(getChunkRadius())} over <em>all</em> vein types &mdash;
 * one global radius sized for the biggest vein in the config. With this pack's 74 definitions that
 * is 225 chunks scanned, yielding roughly 13,500 veins, each of which then runs a 256-column loop:
 * about 3.4 million iterations per generated chunk.
 *
 * <p>Upstream filters per column with {@code inRange} but never asks whether a vein could reach the
 * region at all, so the overwhelming majority of those loops cannot place a single block.
 *
 * <h2>1. Skip veins that cannot reach the region</h2>
 *
 * <p>The soundness argument, for cluster veins (all 74 in this pack):
 * {@code VeinCluster.inRange} is {@code dx*dx + dz*dz < horizontalSize^2 * size} with
 * {@code size = 0.7f + random * 0.3f}, so {@code size < 1} and the reach is at most
 * {@code horizontalSize} blocks. {@code getChunkRadius()} is {@code 1 + (horizontalSize >> 4)}, so
 * {@code getChunkRadius() * 16 >= 16 + (horizontalSize - 15) > horizontalSize}. The bound below is
 * therefore never tighter than the vein's real reach.
 *
 * <p>If a future vein type reaches further than {@code horizontalSize} this would under-generate it.
 * {@code getChunkRadius()} is the mod's own answer to "how far can this type reach", so that would
 * be an upstream inconsistency rather than an assumption made here &mdash; but it is the thing to
 * re-check if a new vein type is ever added.
 *
 * <h2>2. Test range before resolving the biome</h2>
 *
 * <p>Upstream evaluates {@code matchesBiome(getBiome(pos)) && inRange(x, z)}, doing the expensive
 * lookup for every column before the cheap range test rules most of them out. Because of the
 * {@code &&}, returning an arbitrary biome when the column is out of range is safe: {@code inRange}
 * vetoes the column regardless of what {@code matchesBiome} answered. No sentinel value and no
 * dependency on the companion mixin.
 *
 * <h2>3. Cache the biome per column</h2>
 *
 * <p>For columns that survive, {@code world.getBiome} is {@code isBlockLoaded} plus {@code getChunk}
 * plus a registry lookup, and the region is a single 16x16 area revisited by every overlapping vein.
 * A miss defers to the original call, so biome selection itself is untouched. Entries are tied to the
 * world that produced them, because {@code WorldGenVeins} is one instance shared by every dimension
 * (and, in singleplayer, by every save opened in the session); see {@link ChunkBiomeCache}.
 *
 * @see MixinAbstractVeinType for the companion {@code matchesBiome} cache
 */
@Pseudo
@Mixin(targets = "com.alcatrazescapee.oreveins.world.WorldGenVeins", remap = false)
public abstract class MixinWorldGenVeins {

    // Both injectors name the full descriptor rather than a bare "generate": the class has two
    // methods of that name and only the private one loops over columns. A bare name resolves to the
    // public IWorldGenerator entry point and fails at boot with "Scanned 0 target(s)".
    @Unique
    private final ChunkBiomeCache mtmixins$biomeCache = new ChunkBiomeCache();

    @Inject(
            method = "generate(Lnet/minecraft/world/World;Ljava/util/Random;IILcom/alcatrazescapee/oreveins/api/IVein;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void mtmixins$skipUnreachableVein(World world, Random random, int xOff, int zOff,
                                              IVein<?> vein, CallbackInfo ci) {
        final BlockPos pos = vein.getPos();
        final int reach = vein.getType().getChunkRadius() << 4;

        if (pos.getX() + reach < xOff || pos.getX() - reach >= xOff + 16
                || pos.getZ() + reach < zOff || pos.getZ() - reach >= zOff + 16) {
            ci.cancel();
        }
    }

    @WrapOperation(
            method = "generate(Lnet/minecraft/world/World;Ljava/util/Random;IILcom/alcatrazescapee/oreveins/api/IVein;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;getBiome(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/biome/Biome;",
                    remap = true
            )
    )
    private Biome mtmixins$biomeOnlyWhenInRange(World world, BlockPos pos, Operation<Biome> original,
                                                @Local(index = 5) IVein<?> vein,
                                                @Local(index = 6) int x,
                                                @Local(index = 7) int z) {
        // Out of range: the caller's `matchesBiome(...) && inRange(x, z)` is false whatever we
        // return, so skip the lookup entirely.
        if (!vein.inRange(x, z)) {
            return Biomes.PLAINS;
        }

        // Keyed by world as well as column: this generator instance serves every dimension, and in
        // singleplayer every save loaded in the session.
        Biome cached = this.mtmixins$biomeCache.get(world, pos.getX(), pos.getZ());
        if (cached != null) {
            return cached;
        }

        Biome biome = original.call(world, pos);
        this.mtmixins$biomeCache.put(world, pos.getX(), pos.getZ(), biome);
        return biome;
    }
}
