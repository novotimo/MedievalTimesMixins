package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.FarmLimiter;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B41 &mdash; the per-chunk animal count the throttle reads, and refusing breeding food in a chunk
 * that is at the breeding limit. See {@link MixinWorldFarmThrottle} for the register as a whole.
 *
 * <p>The refusal sits at the head of {@code EntityAnimal.processInteract}, ahead of vanilla's
 * {@code isBreedingItem && getGrowingAge() == 0 && inLove <= 0} branch, so the food is not used. Feeding a
 * baby to make it grow is untouched. Animals that override {@code processInteract} without calling
 * {@code super} for breeding (horses' golden food, Animania) are not stopped here; their births are, by
 * {@link MixinEntityAIMateFarm}, except Animania's, which happen outside {@code EntityAIMate}.
 */
@Mixin(EntityAnimal.class)
public abstract class MixinEntityAnimalFarm implements FarmLimiter.Crowded {

    @Unique
    private int mtmixins$farmCrowd;

    @Override
    public int mtmixins$getFarmCrowd() {
        return this.mtmixins$farmCrowd;
    }

    @Override
    public void mtmixins$setFarmCrowd(int animals) {
        this.mtmixins$farmCrowd = animals;
    }

    @Inject(method = "processInteract", at = @At("HEAD"), cancellable = true)
    private void mtmixins$refuseBreedingWhenCrowded(EntityPlayer player, EnumHand hand,
            CallbackInfoReturnable<Boolean> cir) {
        if (FarmLimiter.refusesFeeding((EntityAnimal) (Object) this, player, hand)) {
            cir.setReturnValue(true);
        }
    }
}
