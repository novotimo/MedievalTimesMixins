package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.MedievalTimesMixins;
import de.maxhenkel.corpse.entities.EntityCorpse;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderEnd;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Register B31 &mdash; shared by {@code MixinEntityCorpseBody} and {@code MixinDeathEventsCorpse}. Puts a
 * corpse that Corpse would leave inside the bottom bedrock layer (or floating in the End's void) on the
 * nearest floor a player can walk to, and stops a corpse that has been set moving.
 *
 * <p>Everything here runs on the server thread.
 */
public final class CorpseBodies {

    /** How far sideways, in blocks, to look for a floor before falling back to the dimension's spawn. */
    private static final int RADIUS = 8;

    /** Column offsets within {@link #RADIUS}, nearest first. */
    private static final int[][] OFFSETS;

    static {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        OFFSETS = offsets.toArray(new int[0][]);
    }

    private CorpseBodies() {
    }

    /**
     * Whether {@code corpse} is somewhere a player cannot reach it: Corpse puts any body below y = 0 at
     * y = 0, which is the bottom bedrock layer in the overworld and the Nether and open void in the End.
     */
    public static boolean needsRescue(EntityCorpse corpse) {
        return corpse.posY < 1.0D;
    }

    /**
     * Moves {@code corpse} onto the nearest floor in its own column or one within {@link #RADIUS}
     * blocks, else onto the floor at the dimension's spawn (the obsidian platform in the End). Returns
     * false, leaving it where it is, when neither has one. Loads the chunks it looks at, so the corpse
     * always lands in a loaded chunk. Never throws.
     */
    public static boolean rescue(EntityCorpse corpse, String when) {
        try {
            World world = corpse.world;
            double fromX = corpse.posX;
            double fromY = corpse.posY;
            double fromZ = corpse.posZ;
            BlockPos spot = findSpot(world, MathHelper.floor(fromX), MathHelper.floor(fromZ));
            if (spot == null) {
                BlockPos anchor = world.provider.getSpawnCoordinate();
                if (anchor == null) {
                    anchor = world.getSpawnPoint();
                }
                spot = findSpot(world, anchor.getX(), anchor.getZ());
            }
            if (spot == null) {
                MedievalTimesMixins.LOG.warn(
                        "Register B31: the corpse of {} {} at ({}, {}, {}) in dimension {} is below the floor and "
                                + "no floor was found near it or at spawn; left where it is.",
                        corpse.getCorpseName(), when, fmt(fromX), fmt(fromY), fmt(fromZ), world.provider.getDimension());
                return false;
            }
            corpse.motionX = 0.0D;
            corpse.motionY = 0.0D;
            corpse.motionZ = 0.0D;
            corpse.setPositionAndUpdate(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
            MedievalTimesMixins.LOG.info(
                    "Register B31: moved the corpse of {} {} from ({}, {}, {}) to ({}, {}, {}) in dimension {}: "
                            + "it was below the floor.",
                    corpse.getCorpseName(), when, fmt(fromX), fmt(fromY), fmt(fromZ),
                    spot.getX(), spot.getY(), spot.getZ(), world.provider.getDimension());
            return true;
        } catch (RuntimeException e) {
            MedievalTimesMixins.LOG.error("Register B31: could not move the corpse of {} off the bottom of the world.",
                    corpse.getCorpseName(), e);
            return false;
        }
    }

    /**
     * Slows a corpse the way vanilla slows a dropped item. Corpse moves its body by its own motion every
     * tick and only ever manages the vertical part, so anything that gave it sideways motion sent it
     * sliding in a straight line until it hit a wall.
     */
    public static void applyFriction(EntityCorpse corpse) {
        if (corpse.motionX == 0.0D && corpse.motionZ == 0.0D) {
            return;
        }
        float friction = 0.98F;
        if (corpse.onGround || corpse.collidedVertically) {
            BlockPos below = new BlockPos(MathHelper.floor(corpse.posX), MathHelper.floor(corpse.posY) - 1,
                    MathHelper.floor(corpse.posZ));
            IBlockState state = corpse.world.getBlockState(below);
            friction = state.getBlock().getSlipperiness(state, corpse.world, below, corpse) * 0.98F;
        }
        corpse.motionX *= friction;
        corpse.motionZ *= friction;
        if (Math.abs(corpse.motionX) < 0.003D) {
            corpse.motionX = 0.0D;
        }
        if (Math.abs(corpse.motionZ) < 0.003D) {
            corpse.motionZ = 0.0D;
        }
    }

    @Nullable
    private static BlockPos findSpot(World world, int x, int z) {
        boolean ceiling = world.provider.isNether()
                || (!world.provider.hasSkyLight() && !(world.provider instanceof WorldProviderEnd));
        int top = world.getActualHeight() - 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] offset : OFFSETS) {
            int cx = x + offset[0];
            int cz = z + offset[1];
            // Under a ceiling (the Nether) the lowest open floor, so the body is not put on the roof;
            // anywhere else the highest, which is where the player will look for it.
            if (ceiling) {
                for (int y = 1; y < top; y++) {
                    if (isSpot(world, pos, cx, y, cz)) {
                        return new BlockPos(cx, y, cz);
                    }
                }
            } else {
                for (int y = top; y >= 1; y--) {
                    if (isSpot(world, pos, cx, y, cz)) {
                        return new BlockPos(cx, y, cz);
                    }
                }
            }
        }
        return null;
    }

    /** Open and dry at y, with something to lie on at y - 1 that is not a treetop. */
    private static boolean isSpot(World world, BlockPos.MutableBlockPos pos, int x, int y, int z) {
        Material here = world.getBlockState(pos.setPos(x, y, z)).getMaterial();
        if (here.blocksMovement() || here.isLiquid()) {
            return false;
        }
        Material floor = world.getBlockState(pos.setPos(x, y - 1, z)).getMaterial();
        return floor.blocksMovement() && floor != Material.LEAVES;
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }
}
