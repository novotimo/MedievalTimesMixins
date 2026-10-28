package com.novotimo.mtmixins.mixin.vanilla;

import java.util.concurrent.locks.Lock;
import net.minecraft.network.datasync.EntityDataManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B44 &mdash; reading an entity's synced data no longer takes a lock.
 *
 * <h2>The cost</h2>
 *
 * <p>Every read of an entity's {@code EntityDataManager} (health, flags, size, Animania's states, Lycanites'
 * tamed byte, ...) goes through the private {@code getEntry}, which takes and releases a
 * {@code ReentrantReadWriteLock} read lock around one {@code HashMap} lookup. With thousands of entities each
 * reading a dozen values a tick, that lock was the single largest self-time frame on prod: 4.5% of the server
 * thread in the 2026-10-28 profile, 5.2% of busy time on 2026-10-22.
 *
 * <h2>Why it can go</h2>
 *
 * <p>The only writer of the map is {@code register}, under the write lock, and it runs while the entity is
 * being constructed, before any other code can see it. After that the map's structure never changes; values
 * change inside its entries, which the lock never protected anyway. Spigot removed the lock for this reason
 * ("Remove DataWatcher Locking": "modifications to this map only occur on initialization of an Entity in its
 * constructor ... the further readlocks are actually useless"), and Paper ships it as "Elide lock in
 * DataWatcher". Only {@code getEntry}'s read lock is elided here; {@code register}'s write lock and the rarely
 * called readers keep theirs.
 *
 * <p>Server only, where entity data is read on the server thread; the client is left as it was. Its own
 * config file, {@code mixins.mtmixins.datalock.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild. Nothing in game changes.
 */
@Mixin(EntityDataManager.class)
public abstract class MixinEntityDataManagerNoLock {

    @Redirect(method = "getEntry",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/locks/Lock;lock()V", remap = false))
    private void mtmixins$skipReadLock(Lock lock) {
    }

    @Redirect(method = "getEntry",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/locks/Lock;unlock()V", remap = false))
    private void mtmixins$skipReadUnlock(Lock lock) {
    }
}
