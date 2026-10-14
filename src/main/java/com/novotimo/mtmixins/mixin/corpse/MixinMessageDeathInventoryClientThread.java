package com.novotimo.mtmixins.mixin.corpse;

import de.maxhenkel.corpse.net.MessageDeathInventory;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B30 &mdash; the same network-thread fault as {@link MixinMessageOpenHistoryClientThread}, in
 * the message that carries a death's items to the client before its item window opens. Corpse stores
 * the record in a plain {@code HashMap} from the network thread, while the client thread reads that map
 * to build the window.
 *
 * <p>Re-queued onto the client thread like the history. The ordering still holds: the server sends this
 * message before the window-open packet, and FML's {@code OpenGuiHandler} queues the window onto the same
 * client-thread queue, so the record is stored before the window is built. Client only.
 */
@Mixin(value = MessageDeathInventory.class, remap = false)
public abstract class MixinMessageDeathInventoryClientThread {

    @Inject(method = "onMessage(Lde/maxhenkel/corpse/net/MessageDeathInventory;Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$onClientThread(MessageDeathInventory message, MessageContext ctx,
                                         CallbackInfoReturnable<IMessage> cir) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread()) {
            MessageDeathInventory handler = (MessageDeathInventory) (Object) this;
            mc.addScheduledTask(() -> handler.onMessage(message, ctx));
            cir.setReturnValue(null);
        }
    }
}
