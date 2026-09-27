package com.novotimo.mtmixins.bridge;

import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective access to the two things register B7 needs from BetterCaves: whether BetterCaves is
 * handling a given dimension, and that dimension's configured liquid altitude.
 *
 * <p>Reflection rather than a {@code compileOnly} dependency because that is all this needs &mdash; two
 * public statics and one public field, no types crossing the boundary except {@code int}. It also means
 * the pack can drop BetterCaves entirely without this mod failing to load.
 *
 * <p>Fails open, like {@link RaidBridge}: if anything cannot be resolved, or a call throws, the bridge
 * disables itself for the session, logs once, and every caller falls back to vanilla behaviour.
 */
public final class BetterCavesBridge {

    private static final String UTILS =
            "com.yungnickyoung.minecraft.bettercaves.util.BetterCavesUtils";
    private static final String CONFIG_LOADER =
            "com.yungnickyoung.minecraft.bettercaves.config.io.ConfigLoader";

    /** {@code public static boolean isDimensionWhitelisted(int)} */
    private static final Method IS_DIMENSION_WHITELISTED;
    /** {@code public static ConfigHolder loadConfigFromFileForDimension(int)} */
    private static final Method LOAD_CONFIG_FOR_DIMENSION;
    /** {@code public ConfigOption<Integer> ConfigHolder.liquidAltitude} */
    private static final Field LIQUID_ALTITUDE;
    /** {@code public T ConfigOption.get()} */
    private static final Method OPTION_GET;

    private static volatile boolean available;

    /**
     * {@code loadConfigFromFileForDimension} reads (and can write) a file per dimension, so it is
     * called at most once per dimension id and the answer kept. BetterCaves does the same thing once
     * per generator in its own {@code initialize(World)}.
     */
    private static final Map<Integer, Integer> ALTITUDE_BY_DIMENSION = new ConcurrentHashMap<>();

    static {
        Method whitelisted = null;
        Method loadConfig = null;
        Field altitude = null;
        Method get = null;
        boolean ok = false;
        try {
            whitelisted = Class.forName(UTILS).getMethod("isDimensionWhitelisted", int.class);
            loadConfig = Class.forName(CONFIG_LOADER).getMethod("loadConfigFromFileForDimension", int.class);
            altitude = loadConfig.getReturnType().getField("liquidAltitude");
            get = altitude.getType().getMethod("get");
            ok = true;
            MedievalTimesMixins.LOG.info("BetterCaves bridge resolved; mineshaft loading is patched (B7).");
        } catch (ClassNotFoundException e) {
            // Expected when the pack does not ship BetterCaves. Nothing to patch, nothing to say.
            MedievalTimesMixins.LOG.debug("BetterCaves not present; B7 inactive.");
        } catch (Throwable t) {
            MedievalTimesMixins.LOG.warn(
                    "BetterCaves is present but its config API did not look the way B7 expects. "
                            + "Mineshaft loading is left to vanilla, which means saved mineshafts in "
                            + "non-whitelisted dimensions may still be skipped.", t);
        }
        IS_DIMENSION_WHITELISTED = whitelisted;
        LOAD_CONFIG_FOR_DIMENSION = loadConfig;
        LIQUID_ALTITUDE = altitude;
        OPTION_GET = get;
        available = ok;
    }

    private BetterCavesBridge() {
    }

    /** @return true when BetterCaves is installed and its API resolved. */
    public static boolean isPresent() {
        return available;
    }

    /**
     * @return true when BetterCaves generates this dimension's caves, i.e. when a mineshaft here is
     *         subject to its liquid-altitude rule. False if unavailable, which is the safe answer:
     *         the caller then uses plain vanilla mineshaft behaviour.
     */
    public static boolean isActiveFor(World world) {
        if (!available || world == null || world.provider == null) {
            return false;
        }
        try {
            return (Boolean) IS_DIMENSION_WHITELISTED.invoke(null, world.provider.getDimension());
        } catch (Throwable t) {
            disable("isDimensionWhitelisted", t);
            return false;
        }
    }

    /**
     * @return this dimension's configured liquid altitude, or null if it cannot be determined, in
     *         which case the caller must fall back to vanilla behaviour rather than guess. Never
     *         defaults to zero: {@code liquidAltitude + 5} with a wrong zero would silently delete
     *         every mineshaft component below y=5.
     */
    public static Integer liquidAltitudeFor(World world) {
        if (!available || world == null || world.provider == null) {
            return null;
        }
        final int dimension = world.provider.getDimension();
        Integer cached = ALTITUDE_BY_DIMENSION.get(dimension);
        if (cached != null) {
            return cached;
        }
        try {
            Object holder = LOAD_CONFIG_FOR_DIMENSION.invoke(null, dimension);
            Object option = LIQUID_ALTITUDE.get(holder);
            Integer value = (Integer) OPTION_GET.invoke(option);
            if (value == null) {
                return null;
            }
            ALTITUDE_BY_DIMENSION.put(dimension, value);
            return value;
        } catch (Throwable t) {
            disable("liquidAltitude lookup", t);
            return null;
        }
    }

    private static void disable(String what, Throwable t) {
        if (available) {
            available = false;
            MedievalTimesMixins.LOG.warn(
                    "BetterCaves bridge threw in {}; disabling it for this session and leaving "
                            + "mineshaft loading to vanilla.", what, t);
        }
    }
}
