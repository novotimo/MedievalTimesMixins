package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.CreativeTakes;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B52 &mdash; items a creative player takes from the creative menu are recorded in Civilizations'
 * transparency log (triage O-01).
 *
 * <h2>Why</h2>
 *
 * <p>Two owners play in creative openly and two play legit; the transparency log is what lets players check
 * that. Commands are recorded by Civilizations itself, but taking items from the creative menu needs no
 * command, so a command log alone would miss the likeliest thing anyone would be accused of.
 *
 * <h2>How</h2>
 *
 * <p>{@code processCreativeInventoryAction} applies a creative client's slot change with one of two
 * {@code Container.putStackInSlot} calls, or drops the stack with {@code EntityPlayerMP.dropItem} (checked
 * with {@code javap}); all three run only for a player in creative. Both are redirected: the slot's contents
 * are read just before, the vanilla call runs unchanged, and {@link CreativeTakes} works out whether the
 * change was a move within the player's own inventory or an item from the menu, and passes only the latter to
 * Civilizations through {@code bridge/TransparencyBridge}. Nothing about the packet's effect changes.
 *
 * <p>Server only. Lives in {@code mixins.mtmixins.creativelog.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(NetHandlerPlayServer.class)
public abstract class MixinNetHandlerPlayServerCreativeTakes {

    @Shadow
    public EntityPlayerMP player;

    @Redirect(method = "processCreativeInventoryAction",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/inventory/Container;putStackInSlot(ILnet/minecraft/item/ItemStack;)V"))
    private void mtmixins$noteCreativeSlot(Container container, int slotId, ItemStack stack) {
        ItemStack before = container.getSlot(slotId).getStack().copy();
        container.putStackInSlot(slotId, stack);
        CreativeTakes.slotSet(this.player, before, stack);
    }

    @Redirect(method = "processCreativeInventoryAction",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/EntityPlayerMP;dropItem(Lnet/minecraft/item/ItemStack;Z)Lnet/minecraft/entity/item/EntityItem;"))
    private EntityItem mtmixins$noteCreativeDrop(EntityPlayerMP target, ItemStack stack, boolean unused) {
        ItemStack copy = stack.copy();
        EntityItem dropped = target.dropItem(stack, unused);
        if (dropped != null) {
            CreativeTakes.dropped(target, copy);
        }
        return dropped;
    }
}
