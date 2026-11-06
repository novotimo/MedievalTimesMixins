package com.novotimo.mtmixins.bridge;

import com.novotimo.mtmixins.MedievalTimesMixins;
import java.lang.reflect.Method;

/**
 * Seam between B56/B57 and Civilizations' coins ({@code compat/CurrencyHooks}): what an item is worth, and taking and
 * giving amounts in any coins with change in the highest denominations. Same shape as {@link TransparencyBridge}:
 * Civilizations is server-only, so it is resolved reflectively, <b>once</b>, in the static initialiser.
 *
 * <p>Fails towards CustomNPCs' own behaviour: without Civilizations nothing is money, so B57 leaves every price to
 * CustomNPCs and B56 hands coin payouts over as the items the trader gives.
 */
public final class CurrencyBridge {

    private static final String HOOKS = "me.qourtenay.Civilizations.compat.CurrencyHooks";

    private static final Method STACK_VALUE;
    private static final Method BALANCE;
    private static final Method CAN_TAKE;
    private static final Method TAKE;
    private static final Method CAN_GIVE;
    private static final Method GIVE;
    private static final Method FORMAT;

    /** Set after the first invocation failure so a broken hook logs once. */
    private static boolean broken;

    static {
        Method stackValue = null, balance = null, canTake = null, take = null, canGive = null, give = null, format = null;
        try {
            Class<?> hooks = Class.forName(HOOKS);
            stackValue = hooks.getMethod("stackValue", Object.class);
            balance = hooks.getMethod("balance", Object.class);
            canTake = hooks.getMethod("canTake", Object.class, long.class, long.class);
            take = hooks.getMethod("take", Object.class, long.class);
            canGive = hooks.getMethod("canGive", Object.class, long.class);
            give = hooks.getMethod("give", Object.class, long.class);
            format = hooks.getMethod("format", long.class);
        } catch (ClassNotFoundException absent) {
            MedievalTimesMixins.LOG.info("Civilizations' coin hooks not present ({} missing); CustomNPCs traders keep "
                    + "their own coin handling.", HOOKS);
            stackValue = null;
        } catch (Throwable changed) {
            MedievalTimesMixins.LOG.error("{} is present but its methods do not match; CustomNPCs traders keep their own "
                    + "coin handling. Check whether Civilizations changed.", HOOKS, changed);
            stackValue = null;
        }
        STACK_VALUE = stackValue;
        BALANCE = balance;
        CAN_TAKE = canTake;
        TAKE = take;
        CAN_GIVE = canGive;
        GIVE = give;
        FORMAT = format;
    }

    private CurrencyBridge() {
    }

    /** False when Civilizations is missing or a hook has failed: then nothing is money. */
    public static boolean available() {
        return STACK_VALUE != null && !broken;
    }

    /** Copper value of the whole stack, or 0 if it is not money (or the bridge is unavailable). */
    public static long stackValue(Object stack) {
        return available() ? (Long) call(STACK_VALUE, 0L, stack) : 0L;
    }

    public static long balance(Object player) {
        return available() ? (Long) call(BALANCE, 0L, player) : 0L;
    }

    /** Whether taking {@code first} then {@code second} copper would succeed, change included. Changes nothing. */
    public static boolean canTake(Object player, long first, long second) {
        return available() && (Boolean) call(CAN_TAKE, false, player, first, second);
    }

    /** All or nothing. */
    public static boolean take(Object player, long amount) {
        return available() && (Boolean) call(TAKE, false, player, amount);
    }

    /** Whether {@link #give} would succeed. Changes nothing. */
    public static boolean canGive(Object player, long amount) {
        return available() && (Boolean) call(CAN_GIVE, false, player, amount);
    }

    /** In the highest denominations. All or nothing. */
    public static boolean give(Object player, long amount) {
        return available() && (Boolean) call(GIVE, false, player, amount);
    }

    public static String format(long amount) {
        return available() ? (String) call(FORMAT, amount + " Copper", amount) : amount + " Copper";
    }

    private static Object call(Method method, Object fallback, Object... args) {
        try {
            Object result = method.invoke(null, args);
            return result == null ? fallback : result;
        } catch (Throwable t) {
            broken = true;
            MedievalTimesMixins.LOG.error("Civilizations' coin hook {} failed; CustomNPCs traders keep their own coin "
                    + "handling until restart.", method.getName(), t);
            return fallback;
        }
    }
}
