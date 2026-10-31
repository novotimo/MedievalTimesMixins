package com.novotimo.mtmixins.mixin.pregen;

import java.io.File;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.world.chunk.storage.RegionFile;
import net.minecraft.world.chunk.storage.RegionFileCache;
import net.minecraft.world.storage.ThreadedFileIOBase;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Register B55 &mdash; Chunk Pregenerator trims the region file cache under the cache's own lock, instead of from its
 * own thread while the server thread is closing the same files.
 *
 * <h2>The fault</h2>
 *
 * <p>At preInit Chunk Pregenerator replaces vanilla's {@code RegionFileCache.REGIONS_BY_FILE} (a {@code HashMap})
 * with an access-ordered {@code LinkedHashMap} ("Made the Pregenerator ThreadSave"). While a pregen task runs, its
 * {@code ChunkThread} loops on its own thread and closes and removes the eldest entries whenever there are more than
 * 100, without taking the lock that every vanilla method of {@code RegionFileCache} holds. When the server thread is
 * inside {@code RegionFileCache.clearRegionFileReferences} at the same moment, its iterator throws
 * {@code ConcurrentModificationException} and the server stops. That method runs on every dimension unload
 * ({@code DimensionManager.unloadWorlds} &rarr; {@code WorldServer.flush}), on every save-all flush and whenever the
 * cache reaches 256 files. Prod crashed this way at 02:17 UTC on 2026-10-30, unloading a dimension during a pregen.
 * The same race can also leave the map itself inconsistent, which would lose region file handles silently.
 *
 * <h2>The fix</h2>
 *
 * <p>{@code ChunkThread.update} is replaced by the same code with the trim inside
 * {@code synchronized (RegionFileCache.class)}, the monitor vanilla's static synchronized methods use. The wait for
 * the file IO thread stays outside the lock, because that thread takes the lock itself to open region files. The other
 * place the pregenerator changes the map, {@code ChunkProcess.getFile}, is {@link MixinChunkProcessLock}. Both sides,
 * since an integrated server pregenerates the same way. Chunk Pregenerator is not obfuscated, so its members are named
 * as they are in the jar with {@code remap = false}; {@code @Pseudo}, so it does nothing without the mod. Lives in
 * {@code mixins.mtmixins.pregenlock.json}, its own file, so it can be switched off in {@code config/mixinbooter.cfg}
 * without a rebuild.
 */
@Pseudo
@Mixin(targets = "pregenerator.impl.processor.generator.ChunkThread", remap = false)
public abstract class MixinChunkThreadLock {

    @Shadow(remap = false)
    static Map<File, RegionFile> MAP;

    /**
     * @author mtmixins
     * @reason B55: trim the shared region file map under RegionFileCache's lock.
     */
    @Overwrite(remap = false)
    private void update() {
        try {
            ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
            if (MAP == null) {
                MAP = ReflectionHelper.getPrivateValue(RegionFileCache.class, null, "REGIONS_BY_FILE", "field_76553_a");
            } else {
                synchronized (RegionFileCache.class) {
                    while (MAP.size() > 100) {
                        Iterator<File> files = MAP.keySet().iterator();
                        if (!files.hasNext()) {
                            break;
                        }
                        RegionFile file = MAP.remove(files.next());
                        if (file != null) {
                            file.close();
                        }
                    }
                }
            }
            Thread.sleep(1L);
        } catch (Exception ignored) {
            // As the original: an interrupted sleep or a failed close ends this pass and the loop runs again.
        }
    }
}
