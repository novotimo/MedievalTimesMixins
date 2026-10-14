package com.novotimo.mtmixins.util;

import de.maxhenkel.corpse.entities.EntityCorpse;
import de.maxhenkel.corpse.gui.ContainerCorpse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * Register B32 &mdash; shared by the looting mixins in {@code mixin/corpse}. Everything here runs on
 * the server thread.
 */
public final class CorpseLoot {

    /** Vanilla's reach for a chest window, squared. */
    private static final double MAX_DISTANCE_SQ = 64.0D;

    /**
     * The corpse whose {@code processInitialInteract} is running. Corpse opens its window by block
     * position and the GUI handler then takes the first body in that block; this says which one was
     * actually clicked. Set and cleared around the call, which opens the window synchronously.
     */
    @Nullable
    private static EntityCorpse clicked;

    private CorpseLoot() {
    }

    public static void setClicked(@Nullable EntityCorpse corpse) {
        clicked = corpse;
    }

    /** The corpse being clicked, if it is the one in block (x, y, z) of {@code world}. */
    @Nullable
    public static EntityCorpse clickedAt(World world, int x, int y, int z) {
        EntityCorpse corpse = clicked;
        if (corpse == null || corpse.world != world || corpse.isDead) {
            return null;
        }
        BlockPos pos = corpse.getPosition();
        return pos.getX() == x && pos.getY() == y && pos.getZ() == z ? corpse : null;
    }

    /** Whether any player has {@code corpse}'s loot window open. */
    public static boolean isOpen(EntityCorpse corpse) {
        for (EntityPlayer player : corpse.world.playerEntities) {
            if (player.openContainer instanceof ContainerCorpse
                    && ((ContainerCorpse) player.openContainer).getCorpse() == corpse) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code ContainerCorpse.canInteractWith}. A window on a body in the world stays open while the body
     * exists, is in the player's world and is within chest reach. A death-history window is built on a
     * corpse that was never spawned and stays open as before. Either closes when the player dies, which
     * drops a stack held on the cursor at the death site instead of losing it on respawn.
     */
    public static boolean canStillUse(EntityPlayer player, EntityCorpse corpse) {
        if (!player.isEntityAlive() || corpse.isDead) {
            return false;
        }
        // Nothing ever kills a history window's corpse, so a removed body is caught above.
        if (corpse.world.getEntityByID(corpse.getEntityId()) != corpse) {
            return true;
        }
        return corpse.world == player.world
                && player.getDistanceSq(corpse.posX, corpse.posY, corpse.posZ) <= MAX_DISTANCE_SQ;
    }
}
