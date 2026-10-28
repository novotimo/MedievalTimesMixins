package com.novotimo.mtmixins.bridge;

import com.novotimo.mtmixins.MedievalTimesMixins;
import java.lang.reflect.Method;

/**
 * Seam between B52 and Civilizations' transparency log ({@code compat/TransparencyHooks}). Same shape as
 * {@link DragonBridge}: Civilizations is server-only, so it is resolved reflectively, <b>once</b>, in the
 * static initialiser. Fails quiet in every direction: a missing or broken log loses an entry, never a packet.
 */
public final class TransparencyBridge {

    private static final String HOOKS = "me.qourtenay.Civilizations.compat.TransparencyHooks";

    private static final Method CREATIVE_TAKE;

    /** Set after the first invocation failure so a broken hook logs once. */
    private static boolean broken;

    static {
        Method take = null;
        try {
            take = Class.forName(HOOKS).getMethod("creativeTake", Object.class, Object.class);
        } catch (ClassNotFoundException absent) {
            MedievalTimesMixins.LOG.info("Civilizations' transparency log not present ({} missing); creative-menu "
                    + "items are not recorded.", HOOKS);
        } catch (Throwable changed) {
            MedievalTimesMixins.LOG.error("{} is present but has no creativeTake(Object, Object); creative-menu items "
                    + "are not recorded. Check whether Civilizations changed.", HOOKS, changed);
        }
        CREATIVE_TAKE = take;
    }

    private TransparencyBridge() {
    }

    public static void creativeTake(Object player, Object stack) {
        if (CREATIVE_TAKE == null || broken) {
            return;
        }
        try {
            CREATIVE_TAKE.invoke(null, player, stack);
        } catch (Throwable t) {
            broken = true;
            MedievalTimesMixins.LOG.error("Civilizations' creativeTake failed; creative-menu items will not be recorded "
                    + "until restart.", t);
        }
    }
}
