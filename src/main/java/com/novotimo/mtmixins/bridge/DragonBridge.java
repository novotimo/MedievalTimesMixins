package com.novotimo.mtmixins.bridge;

import com.novotimo.mtmixins.MedievalTimesMixins;

import java.lang.reflect.Method;

/**
 * Seam between the Ice and Fire dragon mixins and Civilizations' {@code DragonPolicy}.
 *
 * <p>Same shape and same reasons as {@link RaidBridge}: Civilizations is a server-only jar while this
 * mod ships inside the modpack, so a direct reference would be a {@link NoClassDefFoundError} waiting
 * to happen on every client. Everything is resolved <b>once</b> in the static initialiser &mdash; the
 * lookup must never move into the call sites, because these run per block of dragon breath.
 *
 * <h2>Which way each call fails</h2>
 *
 * <p>Deliberately not the same direction, because the two questions have different worst cases:
 *
 * <ul>
 *   <li>{@link #blockChangeAllowed} fails <b>closed</b> once Civilizations has been seen. If the
 *       policy cannot be consulted, refusing the block change leaves a dragon unable to redecorate,
 *       which is a visible, complainable, reversible annoyance. Failing open means a town gets
 *       levelled with nothing journalled, which is none of those things.</li>
 *   <li>{@link #damageAllowed} fails <b>open</b>. This governs combat; a silently pacifist dragon is
 *       far more confusing to debug than a hit that lands.</li>
 * </ul>
 *
 * <p>When Civilizations is absent entirely both fail open, because then there are no claims to
 * protect and Ice and Fire should behave exactly as it does unpatched.
 */
public final class DragonBridge {

    private static final String POLICY = "me.qourtenay.Civilizations.compat.DragonPolicy";

    private static final Method BLOCK_CHANGE_ALLOWED;
    private static final Method DAMAGE_ALLOWED;
    private static final Method ANNOUNCE;

    /** True when the policy class resolved, i.e. there is a claim system worth asking. */
    private static final boolean PRESENT;

    /** Set after the first invocation failure so a broken hook logs once, not once per block. */
    private static boolean broken;

    /** Civilizations is told once that the precise path is live, so it can stand its fallback down. */
    private static volatile boolean announced;

    static {
        Method blockChange = null;
        Method damage = null;
        Method announce = null;
        Class<?> policy = null;

        try {
            policy = Class.forName(POLICY);
        } catch (ClassNotFoundException absent) {
            MedievalTimesMixins.LOG.info(
                    "Civilizations not present ({} missing); Ice and Fire dragon claim policy is inert "
                            + "and dragons behave as unpatched.", POLICY);
        }

        if (policy != null) {
            try {
                blockChange = policy.getMethod("blockChangeAllowed", Object.class, Object.class, Object.class);
                damage = policy.getMethod("damageAllowed", Object.class, Object.class);
                announce = policy.getMethod("announceBreathMixinActive");
                MedievalTimesMixins.LOG.info(
                        "Dragon claim policy resolved from {}; dragon griefing is governed per block and "
                                + "dragon breath damage per victim.", POLICY);
            } catch (Throwable changed) {
                MedievalTimesMixins.LOG.error(
                        "{} is present but does not have the expected methods. Without the policy there is no "
                                + "way to tell claimed land from unclaimed, so dragons will be REFUSED every block "
                                + "change everywhere as a safe default, and dragon damage is left alone. Check "
                                + "whether Civilizations changed.", POLICY, changed);
            }
        }

        BLOCK_CHANGE_ALLOWED = blockChange;
        DAMAGE_ALLOWED = damage;
        ANNOUNCE = announce;
        PRESENT = policy != null;
    }

    private DragonBridge() {
    }

    /**
     * Tells Civilizations, once, that this mixin is handling the breath paths, so its blunt
     * cancel-the-whole-event fallback can stand down.
     *
     * <p>Done lazily on first use rather than at mod init: a mixin's static initialiser is merged into
     * its target and never runs as its own, and the config this mixin lives in can be switched off from
     * {@code config/mixinbooter.cfg} without rebuilding, so mod init cannot know whether it applied.
     * The cost is that the very first dragon breath after a restart may still be cancelled wholesale.
     */
    private static void announceOnce() {
        if (!announced && ANNOUNCE != null) {
            announced = true;
            try {
                ANNOUNCE.invoke((Object)null);
            } catch (Throwable t) {
                MedievalTimesMixins.LOG.warn("Could not tell Civilizations that the dragon mixin is active; "
                        + "its whole-event fallback will stay engaged.", t);
            }
        }
    }

    /** @return true if this dragon may change this block. Fails closed when Civilizations is present. */
    public static boolean blockChangeAllowed(Object world, Object pos, Object dragon) {
        if (!PRESENT) {
            return true;
        }
        announceOnce();
        if (BLOCK_CHANGE_ALLOWED == null || broken) {
            return false;
        }
        try {
            return (Boolean)BLOCK_CHANGE_ALLOWED.invoke(null, world, pos, dragon);
        } catch (Throwable t) {
            fail("blockChangeAllowed", t, "dragons will be refused every block change, claimed or not");
            return false;
        }
    }

    /** @return true if this dragon may hurt this victim. Fails open. */
    public static boolean damageAllowed(Object dragon, Object victim) {
        if (!PRESENT || DAMAGE_ALLOWED == null || broken) {
            return true;
        }
        announceOnce();
        try {
            return (Boolean)DAMAGE_ALLOWED.invoke(null, dragon, victim);
        } catch (Throwable t) {
            fail("damageAllowed", t, "dragon breath damage is no longer filtered");
            return true;
        }
    }

    private static void fail(String which, Throwable t, String consequence) {
        broken = true;
        MedievalTimesMixins.LOG.error(
                "{}.{} threw. Disabling the dragon policy hooks for this session; {}.",
                POLICY, which, consequence, t);
    }
}
