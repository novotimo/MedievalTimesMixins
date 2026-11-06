package com.novotimo.mtmixins.mixin.customnpcs;

import com.novotimo.mtmixins.MedievalTimesMixins;
import com.novotimo.mtmixins.bridge.CurrencyBridge;
import com.novotimo.mtmixins.util.TraderBulk;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import noppes.npcs.NoppesUtilPlayer;
import noppes.npcs.containers.ContainerNPCTrader;
import noppes.npcs.roles.RoleTrader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B57 &mdash; CustomNPCs traders take any coins of the right value, with change.
 *
 * <h2>The problem</h2>
 *
 * <p>A trader's price is two item stacks ({@code RoleTrader.inventoryCurrency}), and {@code canBuy} and
 * {@code NoppesUtilPlayer.consumeItem} match them item for item: a trade priced at 5 gold coins refused a player
 * carrying a 10-gold stack, a 100-gold bag or 50 silver coins, so players kept piles of every denomination and broke
 * bundles open by hand before shopping.
 *
 * <h2>The change</h2>
 *
 * <p>A price that is coins, as Civilizations' currency catalogue values them ({@code CurrencyBridge}), is a value in
 * copper: {@code canBuy} accepts the trade when the player's coins add up to it and the change would fit
 * ({@code CurrencyBridge.canTake}, a dry run), and the two {@code consumeItem} calls in {@code slotClick} take that value
 * from any coins, giving change in the highest denominations. A price that is not coins is checked and taken by
 * CustomNPCs as before. When the value check fails (too little money in the main inventory, or the change would not
 * fit), CustomNPCs' own check runs, so coins in the off hand still pay the exact price as they used to; without
 * Civilizations nothing is money and nothing changes.
 *
 * <p>When the trade is refused anyway, the reason goes to {@link TraderBulk#refused}: a player whose coins cover the
 * price but whose change would not fit is told so (a plain click otherwise just does nothing), and a shift-click names
 * the reason it stopped. Each price taken by value goes to {@link TraderBulk#paid} for the shift-click's total.
 *
 * <p>Works on its own and under B56's shift-click. Server only; CustomNPCs is not obfuscated, so the targets are named
 * as in the jar with {@code remap = false}. In {@code mixins.mtmixins.cnpccoins.json}.
 */
@Mixin(value = ContainerNPCTrader.class, remap = false)
public abstract class MixinContainerNPCTraderCoins {

    @Shadow
    public RoleTrader role;

    /** Whether the last {@code canBuy} passed on value, so the payment that follows is taken by value too. */
    @Unique
    private boolean mtmixins$byValue;

    /** Whether the last {@code canBuy} failed on value only because the change would not fit. */
    @Unique
    private boolean mtmixins$noRoomForChange;

    @Inject(method = "canBuy", at = @At("HEAD"), cancellable = true)
    private void mtmixins$payByValue(ItemStack first, ItemStack second, EntityPlayer player,
                                     CallbackInfoReturnable<Boolean> cir) {
        mtmixins$byValue = false;
        mtmixins$noRoomForChange = false;
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        long firstValue = CurrencyBridge.stackValue(first);
        long secondValue = CurrencyBridge.stackValue(second);
        if (firstValue <= 0L && secondValue <= 0L) {
            return;
        }
        // The price that is not coins, if any, is matched by CustomNPCs as before.
        if (firstValue <= 0L && !isEmpty(first)
                && !NoppesUtilPlayer.compareItems(player, first, role.ignoreDamage, role.ignoreNBT)) {
            return;
        }
        if (secondValue <= 0L && !isEmpty(second)
                && !NoppesUtilPlayer.compareItems(player, second, role.ignoreDamage, role.ignoreNBT)) {
            return;
        }
        if (CurrencyBridge.canTake(player, firstValue, secondValue)) {
            mtmixins$byValue = true;
            cir.setReturnValue(true);
        } else {
            mtmixins$noRoomForChange = CurrencyBridge.balance(player) >= Math.max(0L, firstValue) + Math.max(0L, secondValue);
        }
    }

    /** Only reached when the value check above did not accept the trade, so CustomNPCs' own check decided. */
    @Inject(method = "canBuy", at = @At("RETURN"))
    private void mtmixins$sayWhyNot(ItemStack first, ItemStack second, EntityPlayer player,
                                   CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && player instanceof EntityPlayerMP) {
            TraderBulk.refused((EntityPlayerMP) player, mtmixins$noRoomForChange);
        }
    }

    @Redirect(method = "func_184996_a",
            at = @At(value = "INVOKE",
                    target = "Lnoppes/npcs/NoppesUtilPlayer;consumeItem(Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/item/ItemStack;ZZ)V"))
    private void mtmixins$takePayment(EntityPlayer player, ItemStack price, boolean ignoreDamage, boolean ignoreNBT) {
        long value = mtmixins$byValue ? CurrencyBridge.stackValue(price) : 0L;
        if (value > 0L) {
            if (CurrencyBridge.take(player, value)) {
                if (player instanceof EntityPlayerMP) {
                    TraderBulk.paid((EntityPlayerMP) player, value);
                }
                return;
            }
            MedievalTimesMixins.LOG.warn("B57: taking {} copper from {} failed after the check passed; taking the "
                    + "trader's exact price instead.", value, player.getName());
        }
        NoppesUtilPlayer.consumeItem(player, price, ignoreDamage, ignoreNBT);
    }

    @Unique
    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }
}
