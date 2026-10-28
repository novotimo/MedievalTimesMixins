package com.novotimo.mtmixins.mixin.vanilla;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B46 &mdash; the server remembers the last two chunks it looked up, so most block reads skip the
 * chunk map.
 *
 * <h2>The cost</h2>
 *
 * <p>Every {@code World.getBlockState} on the server goes {@code getChunk} &rarr; {@code provideChunk}
 * &rarr; {@code loadChunk} &rarr; {@code getLoadedChunk}, which looks the chunk up in a hash map of every
 * loaded chunk (VintageFix's {@code VintageChunkMap}, a {@code Long2ObjectOpenHashMap} of at least 8,192
 * slots). Collision checks, pathfinding, random ticks and lighting read thousands of blocks a tick, nearly
 * all in the chunk they read last. On prod's 2026-10-28 profile, {@code loadChunk}'s own time &mdash; that
 * lookup, with no actual loading under it &mdash; was 5.3% of the server thread.
 *
 * <h2>The cache, and why it cannot hand out the wrong chunk</h2>
 *
 * <p>{@code getLoadedChunk}'s map read first checks the last two chunks it returned. A remembered chunk is
 * only used if its own final {@code x}/{@code z} match the requested position <b>and</b> it is still loaded.
 * Every way a chunk leaves the map goes through {@code ChunkProviderServer.tick}, which calls
 * {@code onUnload()} (clearing the loaded flag) before {@code remove}, and no installed mod removes chunks
 * from the map itself (checked: Chunk Pregenerator only adds absent chunks, ForgeEssentials only reads). Both
 * of {@code ChunkProviderServer}'s own {@code put}s also clear the cache. The two fields are plain references
 * validated on every read, so a read from another thread can at worst miss and fall through to the map.
 * Vanilla's {@code unloadQueued = false} after the read still runs on every hit, so a chunk in use is still
 * kept from unloading.
 *
 * <p>VintageFix ships a four-entry cache inside its map and disabled it (commit d6a80966, "could cause
 * server-side corruption if it is faulty"): that one was keyed by position alone and only cleared on
 * {@code remove(long)}. This one never trusts a key, only the chunk's own coordinates and loaded state.
 *
 * <p>Server only. Its own config file, {@code mixins.mtmixins.chunkcache.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild. Nothing in game changes.
 */
@Mixin(ChunkProviderServer.class)
public abstract class MixinChunkProviderServerLastChunk {

    @Unique
    private Chunk mtmixins$lastChunk;

    @Unique
    private Chunk mtmixins$previousChunk;

    @Redirect(method = "getLoadedChunk",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;get(J)Ljava/lang/Object;",
                    remap = false))
    private Object mtmixins$cachedGet(Long2ObjectMap<Chunk> map, long key) {
        int x = (int) key;
        int z = (int) (key >>> 32);
        Chunk last = this.mtmixins$lastChunk;
        if (last != null && last.x == x && last.z == z && last.isLoaded()) {
            return last;
        }

        Chunk previous = this.mtmixins$previousChunk;
        if (previous != null && previous.x == x && previous.z == z && previous.isLoaded()) {
            this.mtmixins$previousChunk = last;
            this.mtmixins$lastChunk = previous;
            return previous;
        }

        Chunk found = map.get(key);
        if (found != null) {
            this.mtmixins$previousChunk = last;
            this.mtmixins$lastChunk = found;
        }

        return found;
    }

    // Forge's three-argument loadChunk has no obfuscated name, hence remap = false.
    @Redirect(method = "loadChunk(IILjava/lang/Runnable;)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;"),
            remap = false)
    private Object mtmixins$forgetOnLoad(Long2ObjectMap<Chunk> map, long key, Object chunk) {
        this.mtmixins$lastChunk = null;
        this.mtmixins$previousChunk = null;
        return map.put(key, (Chunk) chunk);
    }

    @Redirect(method = "provideChunk",
            at = @At(value = "INVOKE",
                    target = "Lit/unimi/dsi/fastutil/longs/Long2ObjectMap;put(JLjava/lang/Object;)Ljava/lang/Object;",
                    remap = false))
    private Object mtmixins$forgetOnGenerate(Long2ObjectMap<Chunk> map, long key, Object chunk) {
        this.mtmixins$lastChunk = null;
        this.mtmixins$previousChunk = null;
        return map.put(key, (Chunk) chunk);
    }
}
