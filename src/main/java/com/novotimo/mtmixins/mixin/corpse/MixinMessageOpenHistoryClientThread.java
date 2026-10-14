package com.novotimo.mtmixins.mixin.corpse;

import de.maxhenkel.corpse.net.MessageOpenHistory;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B30 &mdash; opening Corpse's death history sometimes disconnects the player with "A fatal
 * error has occurred, this connection is terminated".
 *
 * <h2>The bug</h2>
 *
 * <p>Corpse's client handlers run on the network thread, because SimpleNetworkWrapper calls
 * {@code onMessage} there and Corpse never hands the work to the client thread. In
 * {@code MessageOpenHistory} that work is {@code Minecraft.displayGuiScreen(new GUIDeathHistory(...))}.
 * Setting up the screen fires {@code InitGuiEvent.Post}, JEI rebuilds its ingredient overlay on the
 * network thread while the client thread is drawing it, and the result is a
 * {@code ConcurrentModificationException}. FML treats any exception from a packet handler as fatal and
 * drops the connection. Being a race, it fails only some of the time.
 *
 * <h2>Evidence</h2>
 *
 * <p>Tim's client, 15 October 2026, on the test server, straight after {@code /deathhistory}:
 *
 * <pre>
 *   [Netty Client IO #27/ERROR]: Exception caught during firing event ...GuiScreenEvent$InitGuiEvent$Post
 *   java.util.ConcurrentModificationException
 *     at mezz.jei.render.IngredientListBatchRenderer.set(IngredientListBatchRenderer.java:62)
 *     ...
 *     at net.minecraft.client.Minecraft.displayGuiScreen(Minecraft.java:1018)
 *     at de.maxhenkel.corpse.net.MessageOpenHistory.onMessage(MessageOpenHistory.java:39)
 *   [Netty Client IO #27/ERROR]: Network Disconnect: A fatal error has occurred, this connection is terminated
 * </pre>
 *
 * <h2>The fix</h2>
 *
 * <p>Re-queues the handler onto the client thread, the way FML's own {@code OpenGuiHandler} does, and
 * lets Corpse's code run unchanged there. Client only: it has to reach players through the pack.
 */
@Mixin(value = MessageOpenHistory.class, remap = false)
public abstract class MixinMessageOpenHistoryClientThread {

    // The full descriptor, because javac also emitted a synthetic bridge onMessage(IMessage, MessageContext).
    @Inject(method = "onMessage(Lde/maxhenkel/corpse/net/MessageOpenHistory;Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$onClientThread(MessageOpenHistory message, MessageContext ctx,
                                         CallbackInfoReturnable<IMessage> cir) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!mc.isCallingFromMinecraftThread()) {
            MessageOpenHistory handler = (MessageOpenHistory) (Object) this;
            mc.addScheduledTask(() -> handler.onMessage(message, ctx));
            cir.setReturnValue(null);
        }
    }
}
