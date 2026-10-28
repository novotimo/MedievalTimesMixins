package com.novotimo.mtmixins.bridge;

import com.novotimo.mtmixins.MedievalTimesMixins;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.entity.Entity;

/**
 * Seam between register B41's farm limiter and Ice and Fire's "frozen" state, the ice that dragon breath, ice
 * charges and frost weapons put on an entity.
 *
 * <p>Ice and Fire keeps that state in an LLibrary entity property ({@code FrozenEntityProperties}) and counts
 * it down by one in its {@code LivingUpdateEvent} handler. A throttled animal only fires that event on the
 * ticks it is updated, so without this it would stay frozen up to {@code throttle-max-stride} times as long:
 * a 400-tick breath freeze became 3,200 ticks. The farm limiter asks this at each recount and never throttles
 * a frozen animal.
 *
 * <p>Reflective, like the other bridges, so nothing breaks if either mod is missing; resolved <b>once</b> in
 * the static initialiser. Fails open: if anything does not resolve or throws, the answer is "not frozen",
 * which only means the old, slower thaw.
 */
public final class FrozenBridge {

    private static final Object HANDLER;
    private static final Method GET_PROPERTIES;
    private static final Class<?> FROZEN_PROPERTIES;
    private static final Field IS_FROZEN;

    static {
        Object handler = null;
        Method getProperties = null;
        Class<?> frozenProperties = null;
        Field isFrozen = null;
        try {
            Class<?> handlerClass = Class.forName("net.ilexiconn.llibrary.server.entity.EntityPropertiesHandler");
            handler = handlerClass.getField("INSTANCE").get(null);
            getProperties = handlerClass.getMethod("getProperties", Entity.class, Class.class);
            frozenProperties = Class.forName("com.github.alexthe666.iceandfire.entity.FrozenEntityProperties");
            isFrozen = frozenProperties.getField("isFrozen");
        } catch (Throwable absent) {
            MedievalTimesMixins.LOG.info("Ice and Fire's frozen state is not visible to the farm limiter ({}); "
                    + "frozen animals may be throttled. See register B41.", absent.toString());
            handler = null;
        }

        HANDLER = handler;
        GET_PROPERTIES = getProperties;
        FROZEN_PROPERTIES = frozenProperties;
        IS_FROZEN = isFrozen;
    }

    private FrozenBridge() {
    }

    /** True if Ice and Fire currently has {@code entity} frozen in ice. */
    public static boolean isFrozen(Entity entity) {
        if (HANDLER == null) {
            return false;
        }

        try {
            Object properties = GET_PROPERTIES.invoke(HANDLER, entity, FROZEN_PROPERTIES);
            return properties != null && IS_FROZEN.getBoolean(properties);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
