package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.EntityCollisions;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * Register B18, the counter half. Gives every entity a per-tick count of how many pushes it has taken
 * part in, which {@link MixinEntityLivingBaseCollisions} reads to bound the work.
 *
 * <p>The counter lives on {@code Entity} rather than on {@code EntityLivingBase} because the cap has to
 * be symmetric: a cow being shoved by forty neighbours should stop accepting pushes once it has had its
 * share, and the thing on the other side of a push is not always a living entity. Paper keeps its
 * equivalent field, {@code numCollisions}, on {@code Entity} for the same reason.
 *
 * <h2>Why the count is stamped with the world tick</h2>
 *
 * <p>The count is only meaningful for the tick it was taken in, so it is stored with the world's
 * {@code getTotalWorldTime()} and reads as zero once the world has moved on. It is <b>not</b> reset by
 * a hook in the entity's own {@code onUpdate}, which is what the first version did, because that hook
 * is not reached for every entity that can be pushed:
 *
 * <ul>
 *   <li>{@code EntityMinecart.onUpdate} never calls {@code super.onUpdate()} (it carries its own copy
 *       of the portal logic for exactly that reason), so a minecart's count was never cleared;</li>
 *   <li>entities outside the area the world is willing to tick are not updated at all, but a living
 *       entity just inside it still pushes them.</li>
 * </ul>
 *
 * <p>Because the collision mixin refuses a push when <i>either</i> side is at the cap, a count that
 * never clears is permanent: after its eighth push a stationary minecart could no longer be moved by
 * walking into it, because in its own tick a minecart that is standing still only collides with other
 * minecarts, so a player's or mob's push is the only push it gets. A tick stamp needs nothing to run on
 * the entity's side.
 */
@Mixin(Entity.class)
public abstract class MixinEntityCollisionCount implements EntityCollisions.Counted {

    @Shadow
    public World world;

    @Unique
    private int mtmixins$collisionsThisTick;

    /** World tick the count above belongs to. Any other tick means the count is zero. */
    @Unique
    private long mtmixins$collisionTick = Long.MIN_VALUE;

    @Unique
    private long mtmixins$currentTick() {
        return this.world == null ? Long.MIN_VALUE : this.world.getTotalWorldTime();
    }

    public int mtmixins$getCollisionsThisTick() {
        return this.mtmixins$collisionTick == this.mtmixins$currentTick() ? this.mtmixins$collisionsThisTick : 0;
    }

    public void mtmixins$setCollisionsThisTick(int count) {
        this.mtmixins$collisionTick = this.mtmixins$currentTick();
        this.mtmixins$collisionsThisTick = count;
    }
}
