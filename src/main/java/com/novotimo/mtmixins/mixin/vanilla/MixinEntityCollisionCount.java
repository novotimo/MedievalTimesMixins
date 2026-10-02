package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.EntityCollisions;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B18, the counter half. Gives every entity a per-tick count of how many others it has pushed,
 * which {@link MixinEntityLivingBaseCollisions} reads to bound the work.
 *
 * <p>The counter lives on {@code Entity} rather than on {@code EntityLivingBase} because the cap has to
 * be symmetric: a cow being shoved by forty neighbours should stop accepting pushes once it has had its
 * share, and the thing on the other side of a push is not always a living entity. That is Spigot's
 * design, where the field is {@code numCollisions} on {@code Entity}.
 *
 * <p>Reset at the head of {@code onUpdate}, which is where Spigot resets it too. The ordering is not
 * perfectly fair &mdash; an entity ticked late in the list may have had its budget spent by neighbours
 * ticked earlier &mdash; but that is true upstream as well, and over successive ticks it evens out
 * because tick order is stable while which entity pushes first is not.
 */
@Mixin(Entity.class)
public abstract class MixinEntityCollisionCount implements EntityCollisions.Counted {

    @Unique
    private int mtmixins$collisionsThisTick;

    public int mtmixins$getCollisionsThisTick() {
        return this.mtmixins$collisionsThisTick;
    }

    public void mtmixins$setCollisionsThisTick(int count) {
        this.mtmixins$collisionsThisTick = count;
    }

    @Inject(method = "onUpdate", at = @At("HEAD"))
    private void mtmixins$resetCollisionCount(CallbackInfo ci) {
        this.mtmixins$collisionsThisTick = 0;
    }
}
