package com.novotimo.mtmixins.util;

import java.lang.ref.WeakReference;

import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;

/**
 * A 16x16 biome cache for one chunk at a time.
 *
 * <p>Lives outside the mixin package on purpose: a mixin cannot cleanly contribute a nested class
 * to its target, and the immutable {@link Entry} below is what makes this safe without locking.
 * Each slot holds a single reference to a key/biome pair, and reference writes are atomic, so a
 * concurrent writer can only ever be observed as the complete old pair or the complete new one
 * &mdash; never a key from one chunk beside a biome from another. Worldgen in 1.12.2 populates
 * chunks on the server thread, so contention is not expected; this just means a surprise cannot
 * corrupt anything.
 *
 * <h2>Entries belong to one world</h2>
 *
 * <p>The owner of this cache, OreVeins' {@code WorldGenVeins}, is a single {@code IWorldGenerator}
 * shared by every dimension, and in singleplayer it outlives the save it was first used on. Chunk
 * coordinates alone therefore do not identify a column: chunk (3, 4) of the Nether, of a mining
 * dimension, or of the next singleplayer world loaded is not chunk (3, 4) of the overworld. Every
 * entry is stamped with the {@link Generation} of the world that wrote it and only answers lookups
 * from that same world, so a lookup from another world is a miss and goes to the real
 * {@code getBiome}.
 *
 * <p>The world is held weakly, so this never keeps an unloaded world alive. Switching worlds is a
 * single reference swap that invalidates every slot at once without clearing them.
 */
public final class ChunkBiomeCache {

    private static final int SIZE = 16 * 16;

    /** Identity of the world whose biomes the current entries describe. */
    private static final class Generation {
        final WeakReference<World> world;

        Generation(World world) {
            this.world = new WeakReference<World>(world);
        }
    }

    private static final class Entry {
        final Generation generation;
        final long chunkKey;
        final Biome biome;

        Entry(Generation generation, long chunkKey, Biome biome) {
            this.generation = generation;
            this.chunkKey = chunkKey;
            this.biome = biome;
        }
    }

    private final Entry[] entries = new Entry[SIZE];

    private volatile Generation current = new Generation(null);

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int index(int blockX, int blockZ) {
        return ((blockZ & 15) << 4) | (blockX & 15);
    }

    /**
     * The generation for {@code world}, starting a new one if the entries currently describe a
     * different world. A generation only ever matches the world it was created for, so two
     * threads racing here on different worlds can cost each other hits but never swap answers.
     */
    private Generation generationFor(World world) {
        Generation generation = this.current;
        if (generation.world.get() != world) {
            generation = new Generation(world);
            this.current = generation;
        }
        return generation;
    }

    /** @return the cached biome for this column of this world, or null if it has not been looked up yet. */
    public Biome get(World world, int blockX, int blockZ) {
        Generation generation = this.generationFor(world);
        Entry entry = this.entries[index(blockX, blockZ)];
        if (entry == null || entry.generation != generation) {
            return null;
        }
        return entry.chunkKey == key(blockX >> 4, blockZ >> 4) ? entry.biome : null;
    }

    public void put(World world, int blockX, int blockZ, Biome biome) {
        if (biome != null) {
            this.entries[index(blockX, blockZ)] =
                    new Entry(this.generationFor(world), key(blockX >> 4, blockZ >> 4), biome);
        }
    }
}
