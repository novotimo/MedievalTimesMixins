package com.novotimo.mtmixins.mixin.iceandfire;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B16, capture side &mdash; make the original dragon go away <i>now</i> rather than on its
 * next tick. The release-side half of B16 is in {@link MixinItemDragonHornActive}, which carries the
 * full write-up.
 *
 * <p>1.9.1 ends a capture with a bare {@code setDead()}. That only flags the entity; the world does
 * not act on it until {@code World.updateEntities} reaches it. Anything that intervenes first
 * &mdash; a crash, a relog, a dimension change, the chunk unloading, or just lag &mdash; can leave
 * the original alive while the item already holds a copy of it, which is the precondition for the
 * ghost dragon. The upstream {@code 1.8.4-1.12.2} branch adds {@code world.removeEntity(target)}
 * immediately after {@code setDead()} for exactly this reason, and that is what this reproduces.
 *
 * <p>There is a second thing {@code removeEntity} does that a bare {@code setDead()} does not: it
 * calls {@code removePassengers()} and {@code dismountRidingEntity()}. Capturing a dragon somebody
 * is <i>riding</i> otherwise leaves the rider attached to a dead entity, which is a good candidate
 * for the dismount desync Elegron reported on 27 September ("the server acts like im still on them
 * until I hit shift"). That is a suspicion rather than a confirmed link, but the fix is free either
 * way.
 *
 * <p>Injected at {@code RETURN} and gated on the return value, so this only fires when a capture
 * actually happened rather than on every failed right-click.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.item.ItemDragonHornStatic", remap = false)
public abstract class MixinItemDragonHornStatic {

    /**
     * Both names: {@code itemInteractionForEntity} is inherited from vanilla {@code Item} and is
     * {@code func_111207_a} at runtime, and a {@code @Pseudo} mixin gets no refmap help.
     */
    @Inject(method = {"itemInteractionForEntity", "func_111207_a"}, at = @At("RETURN"), remap = false)
    private void mtmixins$removeCapturedDragonImmediately(ItemStack stack, EntityPlayer playerIn,
                                                          EntityLivingBase target, EnumHand hand,
                                                          CallbackInfoReturnable<Boolean> cir) {
        if (target == null || target.world == null || target.world.isRemote) {
            return;
        }
        if (!Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        // Safe to call on an entity already flagged dead: it detaches riders and passengers and
        // runs the world-side removal that setDead() alone defers.
        target.world.removeEntity(target);
    }
}
