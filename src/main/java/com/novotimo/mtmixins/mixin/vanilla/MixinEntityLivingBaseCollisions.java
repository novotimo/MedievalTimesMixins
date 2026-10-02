package com.novotimo.mtmixins.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.novotimo.mtmixins.util.EntityCollisions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Register B18 &mdash; bound how many entities one entity can push per tick. A backport of Spigot's
 * {@code max-entity-collisions}, which has shipped with a default of 8 for years.
 *
 * <h2>Why</h2>
 *
 * <p>Vanilla {@code collideWithNearbyEntities} pushes against <i>every</i> entity whose box overlaps,
 * with no limit. In a packed pen that is quadratic: forty animals in a 1x1 means forty entities each
 * resolving forty pushes, every tick. Spigot's fix is to give each entity a budget per tick and stop
 * once it is spent, which turns the cost linear in the cap.
 *
 * <h2>What the profile actually said, because it matters</h2>
 *
 * <p>Worth recording so nobody expects more from this than it gives. On the 2 October profile, entity
 * gathering split as: 62% through {@code Entity.move} &rarr; {@code getCollisionBoxes}, which is
 * <i>movement</i> collision; 35% through {@code collideWithNearbyEntities}, which is this; and 4%
 * hoppers. So this addresses about a third of the entity-gathering cost. {@code applyEntityCollision}
 * did not appear in the profile at all, which says the expense is scanning for neighbours rather than
 * the pushing itself &mdash; and this cap stops the scan's <i>results</i> being acted on without
 * stopping the scan. The remaining 62% needs an activation range, which is a different change.
 *
 * <h2>The implementation is Spigot's, not invented here</h2>
 *
 * <p>Both entities in a pair must be under the cap, and both are charged when a push happens. That
 * symmetry is the point: capping only the pusher would let a single animal be shoved by all forty of
 * its neighbours, which is most of the cost in exactly the case this is meant to fix.
 *
 * <p>Wrapping the {@code collideWithEntity} call rather than the whole method leaves vanilla's
 * {@code maxEntityCramming} damage untouched, which runs earlier in the same method. Spigot is explicit
 * that cramming still applies as normal; only the pushing is capped.
 *
 * <p>A cap of 0 means no pushing at all, and a negative cap means vanilla behaviour. Both match Spigot.
 *
 * <p>Not covered: {@code EntityArmorStand} overrides {@code collideWithNearbyEntities}, so armour
 * stands keep vanilla behaviour. They were 1.5% of the gathering, and the override makes them a
 * separate injection for very little return.
 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBaseCollisions {

    @WrapOperation(
        method = "collideWithNearbyEntities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/entity/EntityLivingBase;collideWithEntity(Lnet/minecraft/entity/Entity;)V"
        )
    )
    private void mtmixins$capCollisions(EntityLivingBase self, Entity other, Operation<Void> original) {
        int cap = EntityCollisions.cap();
        if (cap < 0) {
            original.call(self, other);
            return;
        }

        if (cap == 0) {
            return;
        }

        EntityCollisions.Counted pusher = (EntityCollisions.Counted) (Object) self;
        EntityCollisions.Counted pushed = (EntityCollisions.Counted) (Object) other;
        if (pusher.mtmixins$getCollisionsThisTick() >= cap
                || pushed.mtmixins$getCollisionsThisTick() >= cap) {
            return;
        }

        pusher.mtmixins$setCollisionsThisTick(pusher.mtmixins$getCollisionsThisTick() + 1);
        pushed.mtmixins$setCollisionsThisTick(pushed.mtmixins$getCollisionsThisTick() + 1);
        original.call(self, other);
    }
}
