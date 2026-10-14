package com.novotimo.mtmixins.mixin.corpse;

import com.novotimo.mtmixins.util.CorpseLoot;
import de.maxhenkel.corpse.gui.ContainerCorpse;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B32 &mdash; Corpse's loot window never closed on its own: {@code canInteractWith} returned
 * true, so it stayed open after the body was gone, from any distance, and through the looter's death.
 * Now it closes when the body is gone or out of chest reach, or the looter dies; a death-history window
 * is unaffected. See {@link CorpseLoot#canStillUse} and {@link MixinEntityCorpseLoot}.
 *
 * <p>Server only, in {@code mixins.mtmixins.corpseloot.json}.
 */
@Mixin(value = ContainerCorpse.class, remap = false)
public abstract class MixinContainerCorpseValid {

    @Inject(method = "func_75145_c", at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$closeWhenGone(EntityPlayer player, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(CorpseLoot.canStillUse(player, ((ContainerCorpse) (Object) this).getCorpse()));
    }
}
