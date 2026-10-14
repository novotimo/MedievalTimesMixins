package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.ShoulderRiders;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B27 &mdash; a baby dragon on a player's shoulder is lost when the player changes
 * dimension. This sets it down first.
 *
 * <h2>How the dragon is lost</h2>
 *
 * <p>Ice and Fire puts a stage 1 dragon on its owner with {@code dragon.startRiding(player, true)}:
 * the dragon is an ordinary passenger of the player. Vanilla never has anything riding a player, and
 * the one path players take between worlds forgets passengers.
 * {@code PlayerList.transferPlayerToDimension} &mdash; used by portals, by ForgeEssentials'
 * {@code /spawn}, {@code /home}, {@code /warp}, {@code /back} and {@code /tpa} into another world, and
 * by every mod teleporter &mdash; takes the player out of the old world with
 * {@code removeEntityDangerously}, which, unlike {@code removeEntity}, does not unseat passengers.
 * The player arrives alone. The dragon stays in the old world still riding a player who is alive and
 * still lists it as a passenger, so:
 *
 * <ul>
 *   <li>the old world's entity loop skips it every tick, because its vehicle is alive and holds it;</li>
 *   <li>the new world ticks it as the player's passenger, against the wrong world, and files it in
 *   the new world's chunk lists without adding it to that world's entity list or tracker &mdash; so
 *   nobody sees it;</li>
 *   <li>it is never saved: {@code Entity.writeToNBTOptional} skips anything that is riding, and a
 *   passenger is only ever saved inside its vehicle, which for a player is the player file, whose
 *   {@code Passengers} tag vanilla does not read back.</li>
 * </ul>
 *
 * <p>The dragon is gone at the next restart, or sooner when Ice and Fire's logout handler dismounts it
 * into a world it is no longer part of. With the Spawn world being dimension 10, any trip to spawn
 * does this.
 *
 * <h2>The fix</h2>
 *
 * <p>Unseat a player's passengers at the head of {@code World.removeEntityDangerously}, which is
 * exactly what {@code removeEntity} already does for every other removal. The dragon is set down on
 * a free spot beside the player in the world being left, as an ordinary entity that ticks and saves,
 * and the owner can go back for it. The other callers that pass a player here are respawn (the old
 * body: vanilla dismounts its passengers on the next tick anyway, because the body is dead) and a
 * duplicate-UUID player being replaced (a stale object, whose passengers are better on the ground).
 *
 * <p>Upstream has not fixed this. Our jar is the 1.9.1 build of 2020-04-24; the
 * {@code 1.8.4-1.12.2} branch carries commits to February 2022 and touches the shoulder code once,
 * "fix baby dragons teleporting after riding" (7429b19e), which changes how a dragon gets off when the
 * player sneaks. Nothing there handles dimension changes or shutdown.
 *
 * <p>Server only. Its own config file, {@code mixins.mtmixins.shoulderriders.json}, so it can be
 * switched off in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(World.class)
public abstract class MixinWorldShoulderRiders {

    @Inject(method = "removeEntityDangerously", at = @At("HEAD"))
    private void mtmixins$setDownRiders(Entity entityIn, CallbackInfo ci) {
        if (entityIn instanceof EntityPlayerMP && entityIn.isBeingRidden()) {
            ShoulderRiders.setDown((EntityPlayerMP) entityIn, "as the player left the world");
        }
    }
}
