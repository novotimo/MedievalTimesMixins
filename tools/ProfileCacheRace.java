import com.mojang.authlib.Agent;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.ProfileLookupCallback;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.management.PlayerProfileCache;

/**
 * Reproduces the join lockout (register B17) outside the game, in a couple of seconds.
 *
 * <p>Several threads add profiles to a real {@link PlayerProfileCache} at once, exactly as concurrent
 * logins do. {@code addEntry} calls {@code save()}, and {@code save()} walks the {@code gameProfiles}
 * LinkedList to serialise usercache.json, so each add takes a long walk over a list the other threads
 * are free to rearrange. When the list's {@code size} stops matching its node chain,
 * {@code LinkedList$ListItr.next} throws the NullPointerException seen in production &mdash; and the
 * list stays broken afterwards, which is why only a restart cleared it.
 *
 * <p>Run it twice:
 *
 * <pre>  java -cp ... ProfileCacheRace unsafe   # vanilla behaviour: expect the NPE
 *  java -cp ... ProfileCacheRace safe     # what the mixin does: expect no failures</pre>
 *
 * <p>"safe" wraps every call in {@code synchronized (cache)}, which is the same monitor the mixin takes
 * via {@code @WrapMethod}, so the two modes are a faithful before and after.
 */
public final class ProfileCacheRace {

    private static final int THREADS = 8;
    private static final int ROUNDS = 400;

    public static void main(String[] args) throws Exception {
        final boolean safe = args.length > 0 && args[0].equalsIgnoreCase("safe");
        File usercache = File.createTempFile("usercache-race", ".json");
        usercache.deleteOnExit();

        PlayerProfileCache.setOnlineMode(true);
        final PlayerProfileCache cache = new PlayerProfileCache(stubRepository(), usercache);

        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(THREADS);
        final AtomicInteger failures = new AtomicInteger();
        final AtomicReference<Throwable> first = new AtomicReference<Throwable>();

        for (int t = 0; t < THREADS; t++) {
            final int id = t;
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                        return;
                    }

                    for (int i = 0; i < ROUNDS; i++) {
                        try {
                            // Two thirds of the threads log players in; the rest do what a mod querying
                            // the cache from its own thread does.
                            if (id % 3 != 0) {
                                GameProfile profile = new GameProfile(UUID.randomUUID(), "p" + id + "_" + i);
                                if (safe) {
                                    synchronized (cache) {
                                        cache.addEntry(profile);
                                    }
                                } else {
                                    cache.addEntry(profile);
                                }
                            } else if (safe) {
                                synchronized (cache) {
                                    cache.getUsernames();
                                    cache.save();
                                }
                            } else {
                                cache.getUsernames();
                                cache.save();
                            }
                        } catch (Throwable thrown) {
                            failures.incrementAndGet();
                            first.compareAndSet(null, thrown);
                        }
                    }

                    done.countDown();
                }
            }, "login-" + t);
            thread.setDaemon(true);
            thread.start();
        }

        long began = System.currentTimeMillis();
        start.countDown();
        done.await();
        long took = System.currentTimeMillis() - began;

        System.out.println("mode              : " + (safe ? "safe (what register B17 does)" : "unsafe (vanilla 1.12.2)"));
        System.out.println("threads x rounds  : " + THREADS + " x " + ROUNDS);
        System.out.println("elapsed           : " + took + " ms");
        System.out.println("failures          : " + failures.get());

        Throwable thrown = first.get();
        if (thrown != null) {
            System.out.println("first failure     : " + thrown);
            StackTraceElement[] trace = thrown.getStackTrace();
            for (int i = 0; i < Math.min(5, trace.length); i++) {
                System.out.println("                    at " + trace[i]);
            }
        }

        System.out.println(failures.get() == 0
                ? "RESULT: no failures - the cache survived concurrent logins"
                : "RESULT: REPRODUCED - this is the join lockout");
    }

    /** Never touches the network: the cache only consults this on a miss, and we never miss. */
    private static GameProfileRepository stubRepository() {
        return new GameProfileRepository() {
            public void findProfilesByNames(String[] names, Agent agent, ProfileLookupCallback callback) {
                for (String name : names) {
                    callback.onProfileLookupFailed(new GameProfile(null, name),
                            new RuntimeException("offline stub"));
                }
            }
        };
    }
}
