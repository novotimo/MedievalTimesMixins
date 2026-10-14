package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

/**
 * Shared checks and reporting for Register B25, which keeps NaN and infinite coordinates off
 * entities. See {@link com.novotimo.mtmixins.mixin.vanilla.MixinEntityLivingBaseKnockBackNaN} and
 * {@link com.novotimo.mtmixins.mixin.vanilla.MixinEntityPlayerMPNaNGuard} for the why.
 */
public final class NanGuard {

    /** Knockback reports print in full this many times, then every {@link #KNOCKBACK_EVERY}th. */
    private static final int KNOCKBACK_FULL = 20;
    private static final int KNOCKBACK_EVERY = 100;

    private static int knockbacks;

    private NanGuard() {
    }

    public static boolean motionIsFinite(Entity e) {
        return Double.isFinite(e.motionX) && Double.isFinite(e.motionY) && Double.isFinite(e.motionZ);
    }

    public static boolean positionIsFinite(Entity e) {
        return Double.isFinite(e.posX) && Double.isFinite(e.posY) && Double.isFinite(e.posZ);
    }

    public static boolean isFinite(Entity e) {
        return positionIsFinite(e) && motionIsFinite(e)
                && Float.isFinite(e.rotationYaw) && Float.isFinite(e.rotationPitch);
    }

    /** Zeroes whichever motion components are NaN or infinite, keeping the finite ones. */
    public static void clearNonFiniteMotion(Entity e) {
        if (!Double.isFinite(e.motionX)) {
            e.motionX = 0.0D;
        }
        if (!Double.isFinite(e.motionY)) {
            e.motionY = 0.0D;
        }
        if (!Double.isFinite(e.motionZ)) {
            e.motionZ = 0.0D;
        }
    }

    public static float finiteOrZero(float f) {
        return Float.isFinite(f) ? f : 0.0F;
    }

    /**
     * The world spawn of {@code dimension}, raised to the first solid or liquid block, or the
     * overworld's if that dimension is not loaded. Loads the spawn chunk, which the callers' contexts
     * (login, a player tick) already do anyway.
     */
    public static BlockPos safeSpawn(int dimension) {
        WorldServer world = DimensionManager.getWorld(dimension);
        if (world == null) {
            world = DimensionManager.getWorld(0);
        }
        return world.getTopSolidOrLiquidBlock(world.getSpawnPoint());
    }

    /**
     * Logs a knockback that came out non-finite, naming the caller so the mod responsible can be
     * found. Rate limited: these are expected to be rare, but a mob farm could repeat one forever.
     */
    public static void reportKnockback(Entity victim, Entity attacker, double xRatio, double zRatio) {
        int n = ++knockbacks;
        if (n > KNOCKBACK_FULL && n % KNOCKBACK_EVERY != 0) {
            return;
        }
        MedievalTimesMixins.LOG.warn(
                "Register B25: knockback on {} at {},{},{} came out NaN (ratios {}, {}; attacker {}); "
                        + "non-finite motion zeroed. Caller: {}. {} of these so far.",
                victim.getName(), victim.posX, victim.posY, victim.posZ, xRatio, zRatio,
                attacker == null ? "none" : attacker.getClass().getName(), caller(), n);
    }

    /** The first stack frame outside knockBack and this class, i.e. whoever asked for the knockback. */
    private static String caller() {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        boolean pastKnockBack = false;
        for (StackTraceElement frame : trace) {
            String m = frame.getMethodName();
            boolean isKnockBack = "knockBack".equals(m) || "func_70653_a".equals(m) || m.contains("mtmixins$");
            if (isKnockBack) {
                pastKnockBack = true;
            } else if (pastKnockBack && !frame.getClassName().equals(NanGuard.class.getName())) {
                return frame.toString();
            }
        }
        return "unknown";
    }
}
