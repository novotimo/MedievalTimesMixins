package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.MedievalTimesMixins;
import com.novotimo.mtmixins.util.NanGuard;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.DimensionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B25 &mdash; a player whose position goes NaN is kicked on every login until an owner
 * deletes their playerdata. This puts them back somewhere real instead.
 *
 * <p>{@link MixinEntityLivingBaseKnockBackNaN} closes the one source of NaN positions found in prod's
 * logs. This is the net under it, for sources nobody has found yet: a mod that teleports to a
 * computed point, a vehicle or mount whose own position went bad, a {@code /tp} to a player who is
 * already at NaN (which is how Agent_Of_KGB caught it on 13 October).
 *
 * <h2>Two checks</h2>
 *
 * <ol>
 *   <li><b>Every tick</b>, at the head of {@code onUpdate}: if the player's position, motion or
 *   rotation is not finite, they are moved back to the last position this check saw as finite in the
 *   same dimension (their dimension's spawn if there is none), with motion zeroed, through
 *   {@code NetHandlerPlayServer.setPlayerLocation}. That also tells the client and makes the server
 *   ignore movement packets until the client confirms the teleport. Otherwise the current position is
 *   remembered as the last good one. The cost is six finiteness checks per player per tick.</li>
 *   <li><b>On load</b>, at the tail of {@code readEntityFromNBT}: a player saved at NaN, as
 *   ripmaxwell2013 and Agent_Of_KGB were, is moved to the spawn of the dimension they were saved in
 *   before vanilla sends the login position. Without this the client is kicked before the player's
 *   first tick, so the tick check never gets a chance. This is the case that previously needed an
 *   owner to delete the player's file.</li>
 * </ol>
 *
 * <p>Both log a warning naming the player and the bad and new coordinates. Neither should ever fire
 * if B25's knockback fix covers every source, so any line from here is worth reading.
 *
 * <p>Server only. Its own config file, {@code mixins.mtmixins.nanguard.json}, so it can be switched
 * off in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(EntityPlayerMP.class)
public abstract class MixinEntityPlayerMPNaNGuard {

    @Unique
    private boolean mtmixins$hasGoodPos;
    @Unique
    private int mtmixins$goodDim;
    @Unique
    private double mtmixins$goodX;
    @Unique
    private double mtmixins$goodY;
    @Unique
    private double mtmixins$goodZ;

    @Inject(method = "onUpdate", at = @At("HEAD"))
    private void mtmixins$rescueNonFinite(CallbackInfo ci) {
        EntityPlayerMP self = (EntityPlayerMP) (Object) this;
        if (NanGuard.isFinite(self)) {
            this.mtmixins$hasGoodPos = true;
            this.mtmixins$goodDim = self.dimension;
            this.mtmixins$goodX = self.posX;
            this.mtmixins$goodY = self.posY;
            this.mtmixins$goodZ = self.posZ;
            return;
        }

        double x;
        double y;
        double z;
        String to;
        if (this.mtmixins$hasGoodPos && this.mtmixins$goodDim == self.dimension) {
            x = this.mtmixins$goodX;
            y = this.mtmixins$goodY;
            z = this.mtmixins$goodZ;
            to = "their last good position";
        } else {
            BlockPos spawn = NanGuard.safeSpawn(self.dimension);
            x = spawn.getX() + 0.5D;
            y = spawn.getY();
            z = spawn.getZ() + 0.5D;
            to = "spawn";
        }

        MedievalTimesMixins.LOG.warn(
                "Register B25: {} had a non-finite position {},{},{} (motion {},{},{}) in dimension {}; "
                        + "moved to {} {},{},{}.",
                self.getName(), self.posX, self.posY, self.posZ, self.motionX, self.motionY, self.motionZ,
                self.dimension, to, x, y, z);

        self.motionX = 0.0D;
        self.motionY = 0.0D;
        self.motionZ = 0.0D;
        self.fallDistance = 0.0F;
        float yaw = NanGuard.finiteOrZero(self.rotationYaw);
        float pitch = NanGuard.finiteOrZero(self.rotationPitch);

        // A mount at NaN would put its rider straight back there on the next updateRidden. Not
        // dismounting: vanilla's dismount places the rider beside the mount, i.e. at NaN, and sends
        // that to the client. Move the mount instead.
        Entity mount = self.getLowestRidingEntity();
        if (mount != self && !NanGuard.isFinite(mount)) {
            mount.motionX = 0.0D;
            mount.motionY = 0.0D;
            mount.motionZ = 0.0D;
            mount.setLocationAndAngles(x, y, z, NanGuard.finiteOrZero(mount.rotationYaw),
                    NanGuard.finiteOrZero(mount.rotationPitch));
        }
        if (self.connection != null) {
            self.connection.setPlayerLocation(x, y, z, yaw, pitch);
        } else {
            self.setLocationAndAngles(x, y, z, yaw, pitch);
        }
    }

    @Inject(method = "readEntityFromNBT", at = @At("TAIL"))
    private void mtmixins$repairSavedNonFinite(NBTTagCompound compound, CallbackInfo ci) {
        EntityPlayerMP self = (EntityPlayerMP) (Object) this;
        if (NanGuard.isFinite(self)) {
            return;
        }

        // safeSpawn falls back to the overworld's spawn when the saved dimension is not loaded; keep
        // the player's dimension consistent with the coordinates it hands back.
        if (DimensionManager.getWorld(self.dimension) == null) {
            self.dimension = 0;
        }
        BlockPos spawn = NanGuard.safeSpawn(self.dimension);
        MedievalTimesMixins.LOG.warn(
                "Register B25: {} was saved at a non-finite position {},{},{} (motion {},{},{}) in dimension {}; "
                        + "loading them at that dimension's spawn {} instead.",
                self.getName(), self.posX, self.posY, self.posZ, self.motionX, self.motionY, self.motionZ,
                self.dimension, spawn);

        self.motionX = 0.0D;
        self.motionY = 0.0D;
        self.motionZ = 0.0D;
        self.fallDistance = 0.0F;
        self.setLocationAndAngles(spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                NanGuard.finiteOrZero(self.rotationYaw), NanGuard.finiteOrZero(self.rotationPitch));
    }
}
