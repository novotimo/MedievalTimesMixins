package com.novotimo.mtmixins.mixin.ancientwarfare;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.items.IItemHandler;
import net.shadowmage.ancientwarfare.npc.container.ContainerNpcPlayerOwnedTrade;
import net.shadowmage.ancientwarfare.npc.trade.POTrade;
import net.shadowmage.ancientwarfare.npc.trade.POTradeList;
import net.shadowmage.ancientwarfare.npc.trade.Trade;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B33 &mdash; Ancient Warfare's player-owned traders take payment for trades they cannot
 * fill.
 *
 * <h2>The bug</h2>
 *
 * <p>Shift-clicking a trade button in {@code GuiNpcPlayerOwnedTrade} repeats the trade until it fails,
 * but the client tests each repeat with {@code performTrade(player, null)}: against the player's own
 * inventory only, never the NPC's backpack. It sends one {@code doTrade} per repeat until the player
 * runs out of payment. The server, {@code ContainerNpcPlayerOwnedTrade.handlePacketData}, calls
 * {@code tradeList.performTrade(player, storage, index)} without asking {@code POTrade.isAvailable}.
 * {@code Trade.doTrade} then takes the payment, puts it in the backpack and voids what does not fit,
 * and hands over only what the backpack still holds. Once the stock runs out, every further repeat
 * costs the full price and pays nothing.
 *
 * <p>The same call with no backpack ({@code storage == null}) pays out copies of the output from
 * nothing. The window hides every trade in that case, so it takes a modified client to send the packet.
 *
 * <h2>The fix</h2>
 *
 * <p>The server refuses a trade the NPC cannot fill, judged by {@code POTrade.isAvailable}, the same
 * test the window uses to list a trade at all, and refuses all trades without a backpack. The
 * client's repeat loop still sends its packets; the extra ones do nothing, and the inventory sync
 * puts back what the client predicted.
 *
 * <p>Server side: the {@code doTrade} branch only ever runs there. In
 * {@code mixins.mtmixins.awtrade.json} with {@link MixinNpcFactionTraderOpen}.
 */
@Mixin(value = ContainerNpcPlayerOwnedTrade.class, remap = false)
public abstract class MixinContainerNpcPlayerOwnedTradeStock {

    @Redirect(method = "handlePacketData",
            at = @At(value = "INVOKE",
                    target = "Lnet/shadowmage/ancientwarfare/npc/trade/POTradeList;performTrade(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraftforge/items/IItemHandler;I)V"))
    private void mtmixins$onlyWhatIsInStock(POTradeList list, EntityPlayer player, IItemHandler storage, int index) {
        if (storage == null || index < 0 || index >= list.size()) {
            return;
        }
        Trade trade = list.get(index);
        if (trade instanceof POTrade && !((POTrade) trade).isAvailable(storage)) {
            return;
        }
        list.performTrade(player, storage, index);
    }
}
