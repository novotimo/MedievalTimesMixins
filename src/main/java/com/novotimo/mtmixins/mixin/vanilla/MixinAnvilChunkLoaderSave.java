package com.novotimo.mtmixins.mixin.vanilla;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B19 &mdash; stop {@code Failed to save chunk} losing whole chunks, and name whatever
 * causes it.
 *
 * <h2>The fault</h2>
 *
 * <pre>
 * [Server thread/ERROR] [minecraft/AnvilChunkLoader]: Failed to save chunk
 * java.util.ConcurrentModificationException
 *     at java.util.ArrayList$Itr.checkForComodification(ArrayList.java:911)
 *     at java.util.ArrayList$Itr.next(ArrayList.java:861)
 *     at com.google.common.collect.Iterators$3.next(Iterators.java:174)
 *     at AnvilChunkLoader.func_75820_a(AnvilChunkLoader.java:356)
 *     at AnvilChunkLoader.func_75816_a(AnvilChunkLoader.java:174)
 * </pre>
 *
 * <p>{@code writeChunkToNBT} walks every one of the chunk's sixteen entity lists and calls
 * {@code writeToNBTOptional} on each entity. Those lists are {@code ClassInheritanceMultiMap},
 * whose {@code iterator()} hands back a Guava {@code unmodifiableIterator} over its backing
 * {@code ArrayList} &mdash; that wrapper is the {@code Iterators$3} in the trace. If anything
 * adds to or removes from the list while the loop is running, the next {@code next()} throws.
 *
 * <h2>Why it matters more than it looks</h2>
 *
 * <p>The per-entity {@code writeToNBTOptional} call is already inside a try/catch, which is why a
 * mod that throws there gets the polite "An Entity type ... has thrown an exception" message. This
 * exception is raised by the iterator itself, outside that try, so it escapes to
 * {@code saveChunk}'s own catch &mdash; and {@code saveChunk} abandons the chunk before
 * {@code addChunkToPending}. The chunk is <b>never written</b>. Not just its entities: its blocks,
 * tile entities and inventories are all discarded, and whatever was last on disk is what comes back
 * on reload. On the production log it fired on consecutive autosaves 45 seconds apart, so this was
 * steady loss rather than a rare race.
 *
 * <h2>The fix</h2>
 *
 * <p>Iterate a copy. The loop then cannot be invalidated no matter what the entities' write methods
 * do, and the chunk saves. This is the same move Paper makes for vanilla's other unguarded
 * iterations, and it is strictly better than the current behaviour in every case:
 *
 * <ul>
 * <li>An entity <i>added</i> during the loop is missing from the snapshot, so it is not written
 *     this pass. It is still in the world and goes out with the next autosave.</li>
 * <li>An entity <i>removed</i> during the loop is still in the snapshot. If it was killed,
 *     {@code writeToNBTOptional} returns false and it is skipped anyway. If it merely migrated to a
 *     neighbouring chunk, both chunks write it and the reload produces two copies of one UUID
 *     &mdash; which B15 then resolves. A duplicate that B15 cleans up is a much smaller problem
 *     than silently dropping the chunk.</li>
 * </ul>
 *
 * <p>Copying with {@code new ArrayList<>(collection)} itself iterates, so it can throw for the same
 * reason if the mutation is coming from another thread. That is retried, and if it still fails the
 * live iterator is returned, leaving behaviour exactly as it is today rather than worse.
 *
 * <h2>Keeping the root cause findable</h2>
 *
 * <p>A snapshot hides the symptom, so this also reports it. The returned iterator watches the live
 * list's size and, the first time it changes, logs the chunk, the entity written immediately
 * beforehand and the entity it was about to hand out. The entity written just before the size
 * changed is the one whose {@code writeToNBT} did it. If the size changes without a write in
 * between, the mutation came from another thread and is reported as such, which is a different bug
 * needing a different fix.
 *
 * <h2>Reading the stack trace</h2>
 *
 * <p>Worth recording: the line numbers in that trace do not match vanilla source. Alfheim mixes
 * into this same class ({@code AnvilChunkLoaderMixin} injects at HEAD and RETURN of
 * {@code writeChunkToNBT}, {@code saveChunk} and {@code readChunkFromNBT} to carry its neighbour
 * light data) and NotEnoughIDs runs an ASM transformer group on it for extended block ids. Both
 * shift the line numbering. Neither touches the entity loop, so neither is the cause.
 *
 * <p>Kept in its own mixin config ({@code mixins.mtmixins.chunksave.json}) so it can be switched
 * off from {@code config/mixinbooter.cfg} without taking the other groups with it, which matters
 * here because two other mods transform this class.
 */
@Mixin(AnvilChunkLoader.class)
public abstract class MixinAnvilChunkLoaderSave {

    @Unique
    private static final Logger MTMIXINS$LOG = LogManager.getLogger("mtmixins/chunksave");

    /** Rate limit: this sits in the save path, so a stuck cause must not become a log flood. */
    @Unique
    private static final long MTMIXINS$REPORT_INTERVAL_MS = 60000L;

    @Unique
    private static long mtmixins$lastReport;

    @Unique
    private static long mtmixins$mutationsSeen;

    @Unique
    private static long mtmixins$copyRetries;

    @Redirect(
        method = "writeChunkToNBT",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/ClassInheritanceMultiMap;iterator()Ljava/util/Iterator;"
        )
    )
    private Iterator<Entity> mtmixins$snapshotEntities(ClassInheritanceMultiMap<Entity> live,
                                                       Chunk chunk, World world, NBTTagCompound nbt) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                List<Entity> copy = new ArrayList<Entity>(live);
                return new Watched(copy.iterator(), live, copy.size(), chunk);
            } catch (ConcurrentModificationException e) {
                mtmixins$copyRetries++;
            }
        }
        // Three failed copies means something off-thread is mutating this list continuously.
        // Hand back the live iterator so we are never worse than unpatched vanilla.
        mtmixins$report(chunk, null, null, -1, -1, true);
        return live.iterator();
    }

    @Unique
    private static void mtmixins$report(Chunk chunk, Entity previous, Entity current,
                                        int expected, int actual, boolean offThread) {
        mtmixins$mutationsSeen++;
        long now = System.currentTimeMillis();
        if (now - mtmixins$lastReport < MTMIXINS$REPORT_INTERVAL_MS) {
            return;
        }
        mtmixins$lastReport = now;
        String where = chunk == null ? "unknown chunk"
                : ("chunk (" + chunk.x + ", " + chunk.z + ") dim "
                   + (chunk.getWorld() == null ? "?" : chunk.getWorld().provider.getDimension()));
        if (offThread) {
            MTMIXINS$LOG.warn("Chunk entity list in {} is being modified from another thread "
                    + "during save; could not take a stable copy after 3 tries. "
                    + "Saving from the live list, as unpatched vanilla would. "
                    + "({} mutations seen, {} copy retries)",
                    where, mtmixins$mutationsSeen, mtmixins$copyRetries);
            return;
        }
        MTMIXINS$LOG.warn("Chunk entity list changed during save of {} - expected {} entities, "
                + "found {}. The snapshot kept the save working. Last entity written before the "
                + "change: {}. Next entity due: {}. If the former is not null, its writeToNBT is "
                + "what mutated the list. ({} mutations seen so far)",
                where, expected, actual,
                previous == null ? "none yet" : previous.getClass().getName(),
                current == null ? "none" : current.getClass().getName(),
                mtmixins$mutationsSeen);
    }

    /**
     * Iterates the snapshot while watching the live list, so the cause stays attributable even
     * though the save no longer fails.
     */
    private static final class Watched implements Iterator<Entity> {

        private final Iterator<Entity> delegate;
        private final ClassInheritanceMultiMap<Entity> live;
        private final int expected;
        private final Chunk chunk;
        private Entity previous;
        private boolean reported;

        Watched(Iterator<Entity> delegate, ClassInheritanceMultiMap<Entity> live,
                int expected, Chunk chunk) {
            this.delegate = delegate;
            this.live = live;
            this.expected = expected;
            this.chunk = chunk;
        }

        private void check(Entity about) {
            if (this.reported) {
                return;
            }
            if (this.live.size() != this.expected) {
                this.reported = true;
                mtmixins$report(this.chunk, this.previous, about,
                        this.expected, this.live.size(), false);
            }
        }

        @Override
        public boolean hasNext() {
            boolean more = this.delegate.hasNext();
            if (!more) {
                // The mutation may have come from the last entity's write, after which next() is
                // never called again - so the end of the loop has to be checked too.
                this.check(null);
            }
            return more;
        }

        @Override
        public Entity next() {
            Entity e = this.delegate.next();
            this.check(e);
            this.previous = e;
            return e;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException("snapshot iterator is read-only");
        }
    }
}
