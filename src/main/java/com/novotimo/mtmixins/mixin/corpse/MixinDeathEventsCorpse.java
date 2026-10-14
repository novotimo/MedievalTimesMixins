package com.novotimo.mtmixins.mixin.corpse;

import com.novotimo.mtmixins.MedievalTimesMixins;
import com.novotimo.mtmixins.util.CorpseBodies;
import de.maxhenkel.corpse.entities.EntityCorpse;
import de.maxhenkel.corpse.events.DeathEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B31 &mdash; a new corpse below the floor, and a corpse that never had anything in it.
 *
 * <p>Corpse's {@code DeathEvents.playerDeath} handles {@code LivingDropsEvent} at the lowest priority,
 * after every other mod: it does nothing at all if the event was cancelled, and otherwise builds the
 * death record and the body from whatever drops are left. A body with nothing in it removes itself after
 * ten seconds, which looks exactly like "it vanished".
 *
 * <ul>
 *   <li>Moves a new body that {@code createFromDeath} put at y = 0 onto a floor before it is spawned;
 *       see {@link MixinEntityCorpseBody}.</li>
 *   <li>Logs a player death whose drops were cancelled (no body and no death record) or emptied (an
 *       empty body and an empty record) before Corpse saw them. Which mod does that during dragon and
 *       Minotaur fights is not known; these lines say when it happens.</li>
 *   <li>Logs a body the world refused to spawn (its chunk was not loaded), which Corpse ignored.</li>
 * </ul>
 *
 * <p>Server only, in {@code mixins.mtmixins.corpsebody.json}.
 */
@Mixin(value = DeathEvents.class, remap = false)
public abstract class MixinDeathEventsCorpse {

    @Inject(method = "playerDeath(Lnet/minecraftforge/event/entity/living/LivingDropsEvent;)V",
            at = @At("HEAD"), remap = false)
    private void mtmixins$logLostDrops(LivingDropsEvent event, CallbackInfo ci) {
        Entity entity = event.getEntity();
        if (!(entity instanceof EntityPlayerMP) || entity.world.isRemote) {
            return;
        }
        if (event.isCanceled()) {
            MedievalTimesMixins.LOG.warn(
                    "Register B31: {} died at ({}, {}, {}) in dimension {} and another mod cancelled the drops "
                            + "before Corpse saw them: no corpse and no death record.",
                    entity.getName(), (int) entity.posX, (int) entity.posY, (int) entity.posZ, entity.dimension);
            return;
        }
        for (EntityItem item : event.getDrops()) {
            if (!item.getItem().isEmpty()) {
                return;
            }
        }
        MedievalTimesMixins.LOG.info(
                "Register B31: {} died at ({}, {}, {}) in dimension {} with no drops left for Corpse: the corpse "
                        + "is empty and removes itself after ten seconds.",
                entity.getName(), (int) entity.posX, (int) entity.posY, (int) entity.posZ, entity.dimension);
    }

    @Redirect(method = "playerDeath(Lnet/minecraftforge/event/entity/living/LivingDropsEvent;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;func_72838_d(Lnet/minecraft/entity/Entity;)Z"),
            remap = false)
    private boolean mtmixins$spawnOnFloor(World world, Entity corpse) {
        if (corpse instanceof EntityCorpse && CorpseBodies.needsRescue((EntityCorpse) corpse)) {
            CorpseBodies.rescue((EntityCorpse) corpse, "on death");
        }
        boolean spawned = world.spawnEntity(corpse);
        if (!spawned) {
            MedievalTimesMixins.LOG.warn(
                    "Register B31: the world refused to spawn the corpse of {} at ({}, {}, {}) in dimension {}; "
                            + "its items are only in the death record.",
                    corpse instanceof EntityCorpse ? ((EntityCorpse) corpse).getCorpseName() : corpse.getName(),
                    (int) corpse.posX, (int) corpse.posY, (int) corpse.posZ, world.provider.getDimension());
        }
        return spawned;
    }
}
