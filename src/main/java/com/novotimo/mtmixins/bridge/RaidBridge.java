package com.novotimo.mtmixins.bridge;

import com.novotimo.mtmixins.MedievalTimesMixins;

import java.lang.reflect.Method;

/**
 * Seam between the AncientWarfare mixins and TownyForge's {@code RaidPatchHooks}.
 *
 * <p>The hand-edited AncientWarfare jar called {@code com.townyforge.compat.RaidPatchHooks}
 * directly. This mod cannot: it ships with the modpack, and TownyForge is a server-only jar,
 * so a direct reference would be a {@link NoClassDefFoundError} waiting to happen. Resolving
 * it reflectively also means the AncientWarfare mixins survive TownyForge being forked,
 * renamed or moved.
 *
 * <p>Everything is resolved <b>once</b> in the static initialiser. Do not move the lookup
 * into the call sites &mdash; the thing this replaces already burns 0.96 ms/s of prod's tick
 * budget calling {@code Class.forName} from {@code RaidPatchHooks.staticField} on every
 * server tick, and repeating that mistake here would be embarrassing.
 *
 * <p>Every call <b>fails open</b>. If TownyForge is absent or its signatures have changed,
 * AncientWarfare behaves exactly as it does unpatched rather than refusing to break blocks.
 */
public final class RaidBridge {

    /**
     * Both names the hooks class has had, newest first.
     *
     * <p>TownyForge was decompiled into a real source project (Qourte's Civilizations) and its
     * package moved from {@code com.townyforge} to {@code me.qourtenay.Civilizations}. A single
     * hard-coded name would have made this bridge fail <i>open</i> after that deploy: no error,
     * no crash, just AncientWarfare quietly behaving as unpatched. The visible symptom would have
     * been raid terrain damage no longer being recorded, discovered only when a raid ended and
     * restoration put nothing back.
     *
     * <p>Trying both also means the two jars can be deployed in either order, and a rollback of
     * either one still works.
     */
    private static final String[] HOOKS_NAMES = {
            "me.qourtenay.Civilizations.compat.RaidPatchHooks",
            "com.townyforge.compat.RaidPatchHooks",
    };

    /** Whichever of {@link #HOOKS_NAMES} resolved, for log messages. */
    private static final String HOOKS;

    private static final Method BEFORE_AW_BREAK;
    private static final Method FINISH_AW_DIRECT_BREAK;
    private static final Method CAPTURE_RAID_TERRAIN_MUTATION;

    /** Set after the first invocation failure so a broken hook logs once, not once per block. */
    private static boolean broken;

    static {
        Method before = null;
        Method finish = null;
        Method capture = null;
        String resolved = null;

        Class<?> hooks = null;
        for (String candidate : HOOKS_NAMES) {
            try {
                hooks = Class.forName(candidate);
                resolved = candidate;
                break;
            } catch (ClassNotFoundException absent) {
                // Try the next name. Absence of all of them is reported below.
            }
        }

        if (hooks == null) {
            MedievalTimesMixins.LOG.info(
                    "Civilizations/TownyForge not present (tried {}); AncientWarfare raid hooks are inert "
                            + "and AW behaves as unpatched.", java.util.Arrays.toString(HOOKS_NAMES));
        } else {
            try {
                before = hooks.getMethod("beforeAwBreak", Object.class, Object.class);
                finish = hooks.getMethod("finishAwDirectBreak");
                capture = hooks.getMethod("captureRaidTerrainMutation", Object.class, Object.class);
                MedievalTimesMixins.LOG.info(
                        "Raid hooks resolved from {}; AncientWarfare raid guard is active.", resolved);
            } catch (Throwable changed) {
                MedievalTimesMixins.LOG.error(
                        "{} is present but does not have the expected methods. The AncientWarfare raid guard is "
                                + "DISABLED - cannons will damage claimed land. Check whether the mod changed.",
                        resolved, changed);
            }
        }

        HOOKS = resolved;
        BEFORE_AW_BREAK = before;
        FINISH_AW_DIRECT_BREAK = finish;
        CAPTURE_RAID_TERRAIN_MUTATION = capture;
    }

    private RaidBridge() {
    }

    /** @return true if AncientWarfare may break this block. Fails open. */
    public static boolean beforeAwBreak(Object world, Object pos) {
        if (BEFORE_AW_BREAK == null || broken) {
            return true;
        }
        try {
            return (Boolean) BEFORE_AW_BREAK.invoke(null, world, pos);
        } catch (Throwable t) {
            fail("beforeAwBreak", t);
            return true;
        }
    }

    public static void finishAwDirectBreak() {
        if (FINISH_AW_DIRECT_BREAK == null || broken) {
            return;
        }
        try {
            FINISH_AW_DIRECT_BREAK.invoke(null);
        } catch (Throwable t) {
            fail("finishAwDirectBreak", t);
        }
    }

    public static void captureRaidTerrainMutation(Object world, Object pos) {
        if (CAPTURE_RAID_TERRAIN_MUTATION == null || broken) {
            return;
        }
        try {
            CAPTURE_RAID_TERRAIN_MUTATION.invoke(null, world, pos);
        } catch (Throwable t) {
            fail("captureRaidTerrainMutation", t);
        }
    }

    private static void fail(String which, Throwable t) {
        broken = true;
        MedievalTimesMixins.LOG.error(
                "{}.{} threw. Disabling all raid hooks for this session; "
                        + "AncientWarfare will behave as unpatched.", HOOKS, which, t);
    }
}
