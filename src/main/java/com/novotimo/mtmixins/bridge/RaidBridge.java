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

    private static final String HOOKS = "com.townyforge.compat.RaidPatchHooks";

    private static final Method BEFORE_AW_BREAK;
    private static final Method FINISH_AW_DIRECT_BREAK;
    private static final Method CAPTURE_RAID_TERRAIN_MUTATION;

    /** Set after the first invocation failure so a broken hook logs once, not once per block. */
    private static boolean broken;

    static {
        Method before = null;
        Method finish = null;
        Method capture = null;
        try {
            Class<?> hooks = Class.forName(HOOKS);
            before = hooks.getMethod("beforeAwBreak", Object.class, Object.class);
            finish = hooks.getMethod("finishAwDirectBreak");
            capture = hooks.getMethod("captureRaidTerrainMutation", Object.class, Object.class);
            MedievalTimesMixins.LOG.info("TownyForge raid hooks resolved; AncientWarfare raid guard is active.");
        } catch (ClassNotFoundException absent) {
            MedievalTimesMixins.LOG.info(
                    "TownyForge not present ({}); AncientWarfare raid hooks are inert and AW behaves as unpatched.",
                    HOOKS);
        } catch (Throwable changed) {
            MedievalTimesMixins.LOG.error(
                    "{} is present but does not have the expected methods. The AncientWarfare raid guard is "
                            + "DISABLED - cannons will damage claimed land. Check whether TownyForge changed.",
                    HOOKS, changed);
        }
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
                "RaidPatchHooks.{} threw. Disabling all TownyForge raid hooks for this session; "
                        + "AncientWarfare will behave as unpatched.", which, t);
    }
}
