package com.novotimo.mtmixins.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.novotimo.mtmixins.util.FarmLimiter;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Register B41 &mdash; crowded farms are cheaper: animals in a packed chunk are updated every few ticks
 * instead of every tick, and breeding stops past a per-chunk limit. This mixin is the throttle half.
 *
 * <h2>Why</h2>
 *
 * <p>A test-server profile of one chunk packed with cows (2026-10-22): the cows took 28.5% of the server
 * thread, all of it per animal per tick. Moving and its collision lookups 8.5% (the entity half grows with
 * crowding), other mods' {@code LivingUpdateEvent} handlers 7.2% (LLibrary, Ice and Fire, MineFantasy,
 * Lycanites, Wizardry), path following 4.5%, choosing AI tasks 2.7% (Ice and Fire's fear check 1.75%),
 * pushing neighbours 2.3%. Nothing there gets cheaper per cow; only fewer cow-ticks help. Spigot and Paper's
 * entity activation range, and the FarmControl plugin's AI governor, solve the same problem the same way.
 *
 * <h2>The throttle</h2>
 *
 * <p>Wraps the {@code entity.onUpdate()} call in {@code World.updateEntityWithOptionalForce}, so a skipped
 * tick skips the animal's whole update, its subclasses' overrides and every mod's per-entity event, while
 * the chunk bookkeeping and the previous-position copy around it still run (clients see it stand still, not
 * jump). Which animals skip, and how often, is {@link FarmLimiter#skipsUpdate}; the per-chunk counts it
 * reads are refreshed here every {@code recount-ticks} ticks per world.
 *
 * <p>Server only. Its own config file, {@code mixins.mtmixins.farms.json}, with the breeding-cap mixins, so
 * the register can be switched off in {@code config/mixinbooter.cfg} without a rebuild, or in
 * {@code config/mtmixins-farms.properties} without touching the mixins.
 */
@Mixin(World.class)
public abstract class MixinWorldFarmThrottle {

    @Unique
    private long mtmixins$nextFarmRecount;

    @WrapOperation(method = "updateEntityWithOptionalForce",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;onUpdate()V"))
    private void mtmixins$throttleCrowdedAnimals(Entity entity, Operation<Void> original) {
        World world = (World) (Object) this;
        if (!world.isRemote) {
            long now = world.getTotalWorldTime();
            if (now >= this.mtmixins$nextFarmRecount || now < this.mtmixins$nextFarmRecount - 1200L) {
                this.mtmixins$nextFarmRecount = now + FarmLimiter.recountTicks();
                FarmLimiter.recount(world);
            }
        }

        if (!FarmLimiter.skipsUpdate(entity)) {
            original.call(entity);
        }
    }
}
