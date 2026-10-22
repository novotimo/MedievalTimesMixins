package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.FarmLimiter;
import net.minecraft.entity.ai.EntityAIMate;
import net.minecraft.entity.passive.EntityAnimal;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B41 &mdash; no birth in a chunk that is at the breeding limit. See {@link MixinWorldFarmThrottle}
 * for the register as a whole.
 *
 * <p>Catches pairs that fell in love before the chunk filled up, and animals whose feeding the interaction
 * check does not see (horses). The parents are reset exactly as vanilla resets them when another mod
 * cancels the {@code BabyEntitySpawnEvent} in this method: 6000 ticks of cooldown and out of love. Animania
 * gives birth from its own pregnancy code, not here, so it is not capped by this.
 */
@Mixin(EntityAIMate.class)
public abstract class MixinEntityAIMateFarm {

    @Shadow
    @Final
    private EntityAnimal animal;

    @Shadow
    private EntityAnimal targetMate;

    @Inject(method = "spawnBaby", at = @At("HEAD"), cancellable = true)
    private void mtmixins$refuseBirthWhenCrowded(CallbackInfo ci) {
        if (FarmLimiter.refusesBirth(this.animal, this.targetMate)) {
            ci.cancel();
        }
    }
}
