package com.novotimo.mtmixins.mixin.customnpcs;

import com.novotimo.mtmixins.util.TraderBulk;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import noppes.npcs.containers.ContainerNPCTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B56 &mdash; shift-clicking a CustomNPCs trade repeats it, and coin payouts come in the highest
 * denominations.
 *
 * <h2>The problem</h2>
 *
 * <p>{@code ContainerNPCTrader.slotClick} trades only on a plain left click ({@code ClickType.PICKUP}) and puts what
 * the trader gives on the cursor; every other click, shift included, does nothing. A second trade only goes through
 * when its result stacks onto what the cursor already holds ({@code canGivePlayer}), so selling a stack of crops for
 * 24 gold coins meant putting the coins down after every trade or two, and a 64-coin payout after every one.
 *
 * <h2>The change</h2>
 *
 * <p>A shift-click on one of the 18 trades runs CustomNPCs' own single trade over and over ({@link TraderBulk}): the
 * same payment check, the same {@code TraderEvent} for NPC scripts, the same payment taken (by value, when B57 is on).
 * What the trader gives goes into the inventory instead of onto the cursor; coins are added up and paid once at the
 * end through Civilizations, in the highest denominations. It stops when the player cannot pay, a script cancels, the
 * result would not fit (that last trade is undone), or after {@link TraderBulk#MAX_ROUNDS}, and tells the player how
 * many trades it made. A plain left click is unchanged.
 *
 * <p>Server side: the client's copy of the container still does nothing on a shift-click, and the inventory sync
 * brings it the result. CustomNPCs is not obfuscated, so its methods are named as in the jar with {@code remap =
 * false}; {@code func_184996_a} is its override of vanilla's {@code slotClick}. In {@code mixins.mtmixins.cnpcbulk.json}.
 */
@Mixin(value = ContainerNPCTrader.class, remap = false)
public abstract class MixinContainerNPCTraderBulk {

    /** The shift-click in progress, or null. */
    @Unique
    private TraderBulk mtmixins$bulk;

    @Shadow
    private boolean canGivePlayer(ItemStack item, EntityPlayer player) {
        throw new AssertionError();
    }

    @Shadow
    private void givePlayer(ItemStack item, EntityPlayer player) {
        throw new AssertionError();
    }

    @Inject(method = "func_184996_a", at = @At("HEAD"), cancellable = true)
    private void mtmixins$tradeRepeatedly(int slotId, int dragType, ClickType clickType, EntityPlayer player,
                                          CallbackInfoReturnable<ItemStack> cir) {
        if (clickType != ClickType.QUICK_MOVE || slotId < 0 || slotId >= 18 || !(player instanceof EntityPlayerMP)
                || mtmixins$bulk != null) {
            return;
        }
        cir.setReturnValue(ItemStack.EMPTY);
        TraderBulk bulk = new TraderBulk((EntityPlayerMP) player);
        mtmixins$bulk = bulk;
        try {
            Container self = (Container) (Object) this;
            while (bulk.beginRound()) {
                self.slotClick(slotId, 0, ClickType.PICKUP, player);
                if (!bulk.endRound()) {
                    break;
                }
            }
        } finally {
            mtmixins$bulk = null;
            bulk.finish();
        }
    }

    /** The cursor does not matter while the result goes into the inventory; {@link TraderBulk} checks the room. */
    @Redirect(method = "func_184996_a",
            at = @At(value = "INVOKE",
                    target = "Lnoppes/npcs/containers/ContainerNPCTrader;canGivePlayer(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;)Z"))
    private boolean mtmixins$roomForResult(ContainerNPCTrader self, ItemStack item, EntityPlayer player) {
        return mtmixins$bulk != null || canGivePlayer(item, player);
    }

    @Redirect(method = "func_184996_a",
            at = @At(value = "INVOKE",
                    target = "Lnoppes/npcs/containers/ContainerNPCTrader;givePlayer(Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;)V"))
    private void mtmixins$handOver(ContainerNPCTrader self, ItemStack item, EntityPlayer player) {
        if (mtmixins$bulk != null) {
            mtmixins$bulk.deliver(item);
        } else {
            givePlayer(item, player);
        }
    }
}
