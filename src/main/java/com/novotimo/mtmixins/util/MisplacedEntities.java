package com.novotimo.mtmixins.util;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.Entity;

/**
 * Register B38 &mdash; remembers which entities vanilla's {@code Chunk.addEntity} killed because they were
 * loaded into a chunk their position is not in.
 *
 * <p>Only Ice and Fire's entities are tracked: they are the ones whose save ignores {@code isDead}, so the
 * only ones that need telling apart. Everything else is dropped on save by vanilla already.
 *
 * <p>Weak keys, so an entry goes when its entity is unloaded and collected; nothing here is saved. Chunks
 * load entities on the server thread, but the set is synchronised in case a mod loads one elsewhere.
 */
public final class MisplacedEntities {

    private static final String IAF_PACKAGE = "com.github.alexthe666.iceandfire.";

    private static final Set<Entity> MISPLACED = Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<Entity, Boolean>()));

    private static final Map<Entity, Boolean> REPORTED = Collections.synchronizedMap(new WeakHashMap<Entity, Boolean>());

    private MisplacedEntities() {
    }

    /** Called from {@code Chunk.addEntity} just before vanilla kills a misplaced entity. */
    public static void mark(Entity entity) {
        if (entity != null && entity.getClass().getName().startsWith(IAF_PACKAGE)) {
            MISPLACED.add(entity);
        }
    }

    public static boolean isMarked(Entity entity) {
        return entity != null && MISPLACED.contains(entity);
    }

    /** True the first time it is asked about an entity, so each record is reported and backed up once. */
    public static boolean firstReport(Entity entity) {
        return REPORTED.put(entity, Boolean.TRUE) == null;
    }
}
