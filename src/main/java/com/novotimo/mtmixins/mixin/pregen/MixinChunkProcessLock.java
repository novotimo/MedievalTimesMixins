package com.novotimo.mtmixins.mixin.pregen;

import java.io.File;
import java.util.Map;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Register B55 &mdash; the second half: Chunk Pregenerator's {@code ChunkProcess.getFile} reads and adds to
 * {@code RegionFileCache.REGIONS_BY_FILE} (through its {@code fileSystem} reference to the same map) without the
 * cache's lock. The map is access-ordered, so even its {@code get} reorders it, and a vanilla iteration of the map on
 * another thread fails. Replaced by the same code with the map access inside {@code synchronized
 * (RegionFileCache.class)}. See {@link MixinChunkThreadLock} for the crash and the config file.
 */
@Pseudo
@Mixin(targets = "pregenerator.impl.processor.generator.ChunkProcess", remap = false)
public abstract class MixinChunkProcessLock {

    @Shadow(remap = false)
    Map<File, RegionFile> fileSystem;

    @Shadow(remap = false)
    File worldFile;

    /**
     * @author mtmixins
     * @reason B55: read and fill the shared region file map under RegionFileCache's lock.
     */
    @Overwrite(remap = false)
    public RegionFile getFile(int x, int z) {
        File file = new File(this.worldFile, "r." + (x >> 5) + "." + (z >> 5) + ".mca");
        if (!file.exists()) {
            return null;
        }
        synchronized (RegionFileCache.class) {
            RegionFile region = this.fileSystem.get(file);
            if (region == null) {
                region = new RegionFile(file);
                this.fileSystem.put(file, region);
            }
            return region;
        }
    }
}
