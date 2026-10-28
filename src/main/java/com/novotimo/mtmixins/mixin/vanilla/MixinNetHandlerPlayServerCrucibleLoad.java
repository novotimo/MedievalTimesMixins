package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.CrucibleResearch;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketClickWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B43 &mdash; notes which player loads a MineFantasy crucible, so the crucible smelts with their
 * research. See {@link CrucibleResearch} for the rules and
 * {@code mixin/minefantasy/MixinBlockCrucibleResearch} for the fault.
 *
 * <p>Every way a player moves items in a window (clicks, shift-clicks, drags, number keys) reaches the
 * server as one {@code CPacketClickWindow} from that player, so comparing the crucible's inputs before and
 * after {@code processClickWindow} says exactly who added what, even with several players in the window.
 *
 * <p>The snapshot is taken after {@code PacketThreadUtil.checkThreadAndEnqueue}. On the network thread that
 * call re-queues the packet and throws, so only the server thread's pass ever reaches the snapshot or the
 * {@code RETURN}; B37 needed a thread-local for the same two passes because its hook is at {@code HEAD}.
 * MFR's types stay in {@code util/CrucibleResearch}, so nothing here loads MFR classes into
 * {@code NetHandlerPlayServer}.
 *
 * <p>Server only. Lives in {@code mixins.mtmixins.crucible.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServerCrucibleLoad {

    @Shadow
    public EntityPlayerMP player;

    /** The crucible and its inputs before the click being processed, or null if the window is not one. */
    @Unique
    private Object mtmixins$crucibleBeforeClick;

    @Inject(method = "processClickWindow",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketThreadUtil;checkThreadAndEnqueue(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/IThreadListener;)V",
                    shift = At.Shift.AFTER))
    private void mtmixins$snapshotCrucible(CPacketClickWindow packetIn, CallbackInfo ci) {
        this.mtmixins$crucibleBeforeClick = CrucibleResearch.beforeClick(this.player.openContainer);
    }

    @Inject(method = "processClickWindow", at = @At("RETURN"))
    private void mtmixins$noteCrucibleLoad(CPacketClickWindow packetIn, CallbackInfo ci) {
        Object before = this.mtmixins$crucibleBeforeClick;
        if (before != null) {
            this.mtmixins$crucibleBeforeClick = null;
            CrucibleResearch.afterClick(before, this.player);
        }
    }
}
