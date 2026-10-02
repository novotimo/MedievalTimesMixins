package com.novotimo.mtmixins.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.authlib.GameProfile;
import java.util.Date;
import java.util.UUID;
import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.server.management.PlayerProfileCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B17 &mdash; the join lockout. Makes {@link PlayerProfileCache} thread-safe, which it is not,
 * and never was in 1.12.2.
 *
 * <h2>What was happening</h2>
 *
 * <p>Nobody could join until the server was restarted, and it kept coming back: 68 occurrences in the
 * ten days of logs, 52 of them on 29 September. Every one looks like this, always a second or two after
 * an authenticator thread resolved somebody's UUID:
 *
 * <pre>[Server thread/WARN] [NetworkSystem]: Failed to handle packet for /x.x.x.x:51338
 *java.lang.NullPointerException: null
 *	at java.util.LinkedList$ListItr.next(LinkedList.java:893)
 *	at com.google.common.collect.Iterators$8.next(Iterators.java:896)
 *	at com.google.common.collect.Iterators.addAll(Iterators.java:367)
 *	at com.google.common.collect.Lists.newArrayList(Lists.java:165)
 *	at PlayerProfileCache.getEntriesWithLimit
 *	at PlayerProfileCache.save
 *	at PlayerProfileCache.addEntry
 *	at PlayerProfileCache.addEntry</pre>
 *
 * <p>An NPE inside {@code LinkedList$ListItr.next} means one thing: the list's {@code size} no longer
 * agrees with its node chain. {@code hasNext()} is {@code nextIndex < size}, so it says yes, and then
 * {@code next = next.next} dereferences a node that a concurrent write already unlinked. The class holds
 * three shared mutable structures &mdash; two {@code HashMap}s and the {@code gameProfiles} deque, which
 * is a {@code LinkedList} &mdash; and <b>not one method in it is synchronized</b>.
 *
 * <p>The reason it bites on login rather than occasionally is in the call chain above: {@code addEntry}
 * calls {@code save()} <i>on every single login</i>, and {@code save()} walks the whole deque to
 * serialise {@code usercache.json}. So each login takes a long walk over a list that other threads are
 * free to rearrange underneath it. Once the list is torn it stays torn for the life of the process,
 * which is exactly why only a restart clears it.
 *
 * <h2>This is Paper's fix, not ours</h2>
 *
 * <p>Mojang has no ticket for it and never fixed 1.12.2. Paper has carried a patch called
 * <i>Optimize UserCache / Thread Safe</i> for years, which puts {@code synchronized} on the private
 * {@code addEntry}, on the by-name getter and on the usernames getter. That is what this reproduces.
 * Mixin cannot add a modifier to an existing method, so each one is wrapped and the body run inside
 * {@code synchronized (this)} &mdash; the same monitor, released on a throw just as the keyword would.
 *
 * <p>Two deliberate differences from the upstream patch:
 *
 * <ul>
 * <li>Paper also rewrites a {@code containsKey}-then-{@code get} pair in {@code addEntry} into a single
 *     {@code get}. That closes a race between the two calls, which cannot happen once the method holds
 *     the monitor, so it is left alone rather than rewritten for no gain.</li>
 * <li>Paper moves the {@code usercache.json} write onto a background thread. That is a performance
 *     optimisation rather than part of the correctness fix, and doing it means splitting {@code save()}
 *     so the snapshot stays under the lock while only the write leaves it. Skipped for now: the file
 *     write happens under the monitor instead, costing a few milliseconds per login on a cache of this
 *     size. Worth revisiting only if the save ever shows up in a profile.</li>
 * </ul>
 *
 * <p>{@code save} and the by-UUID getter are wrapped too, which upstream's patch of this vintage does
 * not do. {@code save} because it is the method actually doing the walk that crashes, and the by-UUID
 * getter because it reads a {@code HashMap} that the synchronized writers mutate &mdash; a race Paper
 * later closed a different way, by moving those maps to {@code ConcurrentHashMap} so readers need no
 * lock at all. That swap is not done here on purpose: {@code ConcurrentHashMap} rejects null keys where
 * {@code HashMap} accepts them, and a mod handing this cache a profile with no id currently works. A
 * monitor costs nothing on a cache of a few hundred entries and changes no semantics.
 *
 * <p>Java monitors are reentrant, so the nesting these methods already do &mdash;
 * by-name getter into {@code addEntry} into {@code save} &mdash; is safe. Nothing outside this mixin
 * ever takes the cache's monitor, so there is nothing to deadlock against.
 *
 * <p>{@code load()} is left alone, as upstream does: it runs once at startup, before any player or mod
 * thread can reach the cache.
 */
@Mixin(PlayerProfileCache.class)
public abstract class MixinPlayerProfileCache {

    /**
     * One line at startup so the fix can be confirmed from the log rather than assumed. If this line is
     * missing, the mixin did not apply and the lockout is still live.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void mtmixins$announce(CallbackInfo ci) {
        MedievalTimesMixins.LOG.info(
                "Player profile cache hardened: access is now serialised, so a login can no longer tear "
                        + "the usercache list and lock everyone out. Backport of Paper's Optimize UserCache / "
                        + "Thread Safe. See register B17.");
    }

    /**
     * The private overload, not the public one. Everything that adds an entry funnels through here, so
     * wrapping it covers both a mod calling the public {@code addEntry} and the by-name getter calling
     * this directly after a Mojang lookup.
     */
    @WrapMethod(method = "addEntry(Lcom/mojang/authlib/GameProfile;Ljava/util/Date;)V")
    private void mtmixins$syncAddEntry(GameProfile profile, Date expiry, Operation<Void> original) {
        synchronized (this) {
            original.call(profile, expiry);
        }
    }

    /**
     * Note what is inside the monitor here: on a cache miss this method calls out to Mojang over HTTP,
     * so a lookup for an unknown name holds the lock for as long as that request takes. Upstream accepts
     * the same trade, and it is the right way round &mdash; a slow login beats a permanently broken one
     * &mdash; but it is worth knowing it is there. Misses are rare on a returning playerbase.
     */
    @WrapMethod(method = "getGameProfileForUsername")
    private GameProfile mtmixins$syncGetByName(String username, Operation<GameProfile> original) {
        synchronized (this) {
            return original.call(username);
        }
    }

    @WrapMethod(method = "getProfileByUUID")
    private GameProfile mtmixins$syncGetByUuid(UUID uuid, Operation<GameProfile> original) {
        synchronized (this) {
            return original.call(uuid);
        }
    }

    @WrapMethod(method = "getUsernames")
    private String[] mtmixins$syncGetUsernames(Operation<String[]> original) {
        synchronized (this) {
            return original.call();
        }
    }

    /** The method that actually walks the deque, and so the one the crash came out of. */
    @WrapMethod(method = "save")
    private void mtmixins$syncSave(Operation<Void> original) {
        synchronized (this) {
            original.call();
        }
    }
}
