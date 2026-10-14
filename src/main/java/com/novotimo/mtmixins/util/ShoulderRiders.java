package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * Register B27 &mdash; shared by {@code MixinWorldShoulderRiders} and
 * {@code MixinMinecraftServerShoulderRiders}. Sets down whatever is riding a player, the way vanilla's
 * own {@code World.removeEntity} already does, and says so in the log.
 */
public final class ShoulderRiders {

    private ShoulderRiders() {
    }

    /**
     * Dismounts every entity riding {@code player}. {@code EntityLivingBase.dismountRidingEntity}
     * places each one on a free spot beside the player, in the world the player is still in.
     * Never throws: this runs on teleports and on shutdown, and neither may be broken by it.
     */
    public static void setDown(EntityPlayerMP player, String why) {
        try {
            if (!player.isBeingRidden()) {
                return;
            }
            List<Entity> riders = player.getPassengers();
            player.removePassengers();
            StringBuilder names = new StringBuilder();
            for (Entity rider : riders) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                ResourceLocation id = EntityList.getKey(rider);
                names.append(id == null ? rider.getClass().getName() : id.toString())
                        .append(' ').append(rider.getUniqueID())
                        .append(rider.isRiding() ? " (STILL RIDING)" : "");
            }
            // The world's dimension, not player.dimension: transferPlayerToDimension sets that to the
            // destination before it removes the player from the old world.
            MedievalTimesMixins.LOG.info(
                    "Register B27: set down {} riding {} in dimension {} at {},{},{} {}: {}",
                    riders.size(), player.getName(), player.world.provider.getDimension(),
                    (int) Math.floor(player.posX), (int) Math.floor(player.posY), (int) Math.floor(player.posZ),
                    why, names);
        } catch (Throwable t) {
            MedievalTimesMixins.LOG.error("Register B27: could not set down the riders of {} {}",
                    player.getName(), why, t);
        }
    }
}
