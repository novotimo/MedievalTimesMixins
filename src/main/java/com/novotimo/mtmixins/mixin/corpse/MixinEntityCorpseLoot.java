package com.novotimo.mtmixins.mixin.corpse;

import com.novotimo.mtmixins.util.CorpseLoot;
import de.maxhenkel.corpse.entities.EntityCorpse;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B32 &mdash; a corpse deletes itself while someone is looting it, and a second body on the
 * same block cannot be opened.
 *
 * <h2>The bugs</h2>
 *
 * <p>Corpse 1.12.2-1.0.8:
 *
 * <pre>
 *   EntityCorpse.onUpdate:            if (isEmpty() &amp;&amp; age &gt; 200 || forced despawn) setDead()
 *   ContainerCorpse.canInteractWith:  return true
 *   EntityCorpse.processInitialInteract: openGui(..., getPosition())
 *   GuiHandler.getCorpse:             corpses in that block -&gt; findFirst()
 * </pre>
 *
 * <p>A body more than ten seconds old removes itself the moment its slots read empty, without asking
 * whether anyone has it open, and it reads empty while the last stack is on the looter's cursor. The
 * window never closes on its own, so it stays open on a body that is gone. And the window is found by
 * block position, so of two bodies on one block only the first can ever be opened.
 *
 * <h2>The fix</h2>
 *
 * <ul>
 *   <li>Here: the body is not removed while any player has its window open; it goes on the first tick
 *       after the last window closes.</li>
 *   <li>Here and in {@link MixinGuiHandlerClicked}: the window opens on the body that was clicked.</li>
 *   <li>{@link MixinContainerCorpseValid}: the window closes when the body is gone or out of reach, or
 *       the looter dies.</li>
 *   <li>{@link MixinGuiHandlerClickedClient}: the client shows the clicked body's name and page count
 *       too. Cosmetic; the items always come from the server.</li>
 * </ul>
 *
 * <p>Server only, in {@code mixins.mtmixins.corpseloot.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(value = EntityCorpse.class, remap = false)
public abstract class MixinEntityCorpseLoot {

    @Redirect(method = "func_70071_h_",
            at = @At(value = "INVOKE", target = "Lde/maxhenkel/corpse/entities/EntityCorpse;func_70106_y()V"),
            remap = false)
    private void mtmixins$notWhileOpen(EntityCorpse self) {
        if (!CorpseLoot.isOpen(self)) {
            self.setDead();
        }
    }

    @Inject(method = "func_184230_a", at = @At("HEAD"), remap = false)
    private void mtmixins$rememberClicked(EntityPlayer player, EnumHand hand, CallbackInfoReturnable<Boolean> cir) {
        EntityCorpse self = (EntityCorpse) (Object) this;
        if (!self.world.isRemote) {
            CorpseLoot.setClicked(self);
        }
    }

    @Inject(method = "func_184230_a", at = @At("RETURN"), remap = false)
    private void mtmixins$forgetClicked(EntityPlayer player, EnumHand hand, CallbackInfoReturnable<Boolean> cir) {
        CorpseLoot.setClicked(null);
    }
}
