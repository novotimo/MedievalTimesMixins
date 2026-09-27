package com.novotimo.mtmixins.util;

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
 */
public final class ChunkBiomeCache {

    private static final int SIZE = 16 * 16;

    private static final class Entry {
        final long chunkKey;
        final Biome biome;

        Entry(long chunkKey, Biome biome) {
            this.chunkKey = chunkKey;
            this.biome = biome;
        }
    }

    private final Entry[] entries = new Entry[SIZE];

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private static int index(int blockX, int blockZ) {
        return ((blockZ & 15) << 4) | (blockX & 15);
    }

    /** @return the cached biome for this column, or null if it has not been looked up yet. */
    public Biome get(int blockX, int blockZ) {
        Entry entry = this.entries[index(blockX, blockZ)];
        if (entry == null) {
            return null;
        }
        return entry.chunkKey == key(blockX >> 4, blockZ >> 4) ? entry.biome : null;
    }

    public void put(int blockX, int blockZ, Biome biome) {
        if (biome != null) {
            this.entries[index(blockX, blockZ)] = new Entry(key(blockX >> 4, blockZ >> 4), biome);
        }
    }
}
