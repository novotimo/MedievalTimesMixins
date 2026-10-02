package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.MedievalTimesMixins;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * The cap for register B18, and the counter interface the two collision mixins share.
 *
 * <p>Read from {@code config/mtmixins-collisions.properties} rather than a Forge config, for two
 * reasons: mixins apply long before the mod lifecycle gets a chance to load one, and this server's JVM
 * flags are fixed by the host so a system property is not an option either. A plain properties file can
 * be edited from the hosting panel and needs nothing to be running.
 *
 * <pre>  # entities one entity may push per tick. Spigot's default is 8.
 *  # 0 disables entity pushing entirely; a negative value disables the cap.
 *  max-entity-collisions = 8</pre>
 *
 * <p>Read once, on first collision, and cached. Changing it needs a restart, which is the same as
 * Spigot.
 */
public final class EntityCollisions {

    /** Spigot's default, and what it has shipped with for years. */
    private static final int DEFAULT_CAP = 8;

    private static final String FILE_NAME = "mtmixins-collisions.properties";
    private static final String KEY = "max-entity-collisions";

    private static volatile boolean loaded;
    private static volatile int cap = DEFAULT_CAP;

    private EntityCollisions() {
    }

    /**
     * @return how many other entities one entity may push in a tick. Negative means no cap at all, so
     *         vanilla behaviour; zero means no pushing.
     */
    public static int cap() {
        if (!loaded) {
            load();
        }

        return cap;
    }

    private static synchronized void load() {
        if (loaded) {
            return;
        }

        loaded = true;
        File file = new File(new File("config"), FILE_NAME);
        if (!file.isFile()) {
            MedievalTimesMixins.LOG.info(
                    "No {} found, so entity pushing is capped at Spigot's default of {} per entity per "
                            + "tick. Create that file with \"{} = <n>\" to change it. See register B18.",
                    FILE_NAME, DEFAULT_CAP, KEY);
            return;
        }

        InputStream in = null;
        try {
            in = new FileInputStream(file);
            Properties props = new Properties();
            props.load(in);
            String raw = props.getProperty(KEY);
            if (raw != null) {
                cap = Integer.parseInt(raw.trim());
            }

            MedievalTimesMixins.LOG.info(
                    "Entity pushing capped at {} per entity per tick, from {}. See register B18.",
                    cap, FILE_NAME);
        } catch (Exception problem) {
            MedievalTimesMixins.LOG.warn(
                    "Could not read {} ({}), so entity pushing stays at the default cap of {}.",
                    FILE_NAME, problem, DEFAULT_CAP);
            cap = DEFAULT_CAP;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // nothing useful to do about a failed close on a config read
                }
            }
        }
    }

    /**
     * Implemented onto vanilla {@code Entity} by the counter mixin, so the collision mixin can read and
     * bump the count on <i>both</i> entities in a pair &mdash; which is what makes the cap symmetric.
     */
    public interface Counted {

        int mtmixins$getCollisionsThisTick();

        void mtmixins$setCollisionsThisTick(int count);
    }
}
