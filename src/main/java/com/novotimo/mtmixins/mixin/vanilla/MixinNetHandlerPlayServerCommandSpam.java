package com.novotimo.mtmixins.mixin.vanilla;

import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketChatMessage;

import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B37 &mdash; running a few commands in a row no longer kicks a player for spamming.
 *
 * <h2>The fault</h2>
 *
 * <pre>
 * [Server thread/INFO] [minecraft/MinecraftServer]: &lt;name&gt; lost connection: Kicked for spamming
 * </pre>
 *
 * <p>Vanilla's {@code NetHandlerPlayServer.processChatMessage} adds 20 to {@code chatSpamThresholdCount}
 * after every message, chat <i>or command</i>, and {@code update()} takes one off per tick. A non-op past
 * 200 is disconnected. So ten commands faster than one a second is a kick. Civilizations'
 * {@code /town deposit} takes only the stack in the main hand, so a player paying in copper, silver and
 * gold in several stacks runs it once per stack and trips this. Prod's log had 8 such kicks between 4
 * and 14 October 2026, and players reported it on 15 October.
 *
 * <h2>The fix</h2>
 *
 * <p>A command adds {@value #MTMIXINS$COMMAND_WEIGHT} instead of 20; chat is unchanged. Flood protection
 * for commands stays: a burst of 40 commands, or more than four a second kept up, still kicks. That is
 * the same idea as Spigot's {@code spam-exclusions}, without a list to maintain.
 *
 * <p>The 20 is replaced through {@code @ModifyConstant}; it is the only {@code bipush 20} in the method
 * (checked with {@code javap}). Whether the message is a command is noted at {@code HEAD} in a
 * thread-local: this method runs once on the network thread, which only re-queues the packet, and again
 * on the server thread, and a shared field could be overwritten by the next packet's network-thread pass
 * between the server thread's {@code HEAD} and the constant. The test mirrors vanilla's own:
 * {@code StringUtils.normalizeSpace(message).startsWith("/")}.
 *
 * <p>Server only. Lives in {@code mixins.mtmixins.commandspam.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServerCommandSpam {

    @Unique
    private static final int MTMIXINS$COMMAND_WEIGHT = 5;

    @Unique
    private static final ThreadLocal<Boolean> MTMIXINS$IS_COMMAND = new ThreadLocal<Boolean>();

    @Inject(method = "processChatMessage", at = @At("HEAD"))
    private void mtmixins$noteCommand(CPacketChatMessage packetIn, CallbackInfo ci) {
        String message = packetIn == null ? null : packetIn.getMessage();
        MTMIXINS$IS_COMMAND.set(message != null && StringUtils.normalizeSpace(message).startsWith("/"));
    }

    @ModifyConstant(method = "processChatMessage", constant = @Constant(intValue = 20))
    private int mtmixins$commandWeight(int original) {
        return Boolean.TRUE.equals(MTMIXINS$IS_COMMAND.get()) ? MTMIXINS$COMMAND_WEIGHT : original;
    }
}
