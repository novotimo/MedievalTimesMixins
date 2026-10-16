package com.novotimo.mtmixins.mixin.ancientspellcraft;

import baubles.api.BaubleType;
import baubles.api.BaublesApi;
import baubles.api.cap.IBaublesItemHandler;
import electroblob.wizardry.item.ItemArtefact;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B34 &mdash; activating an AncientSpellcraft charm or ring from its keybind fails on a
 * dedicated server, and can lose or duplicate a ring on the way.
 *
 * <h2>The bug</h2>
 *
 * <p>AncientSpellcraft 1.8.1, {@code PacketActivateBauble.castItem}, run on the server thread when a
 * player presses the activate key:
 *
 * <pre>
 *   List&lt;ItemStack&gt; stacks = ASBaublesIntegration.getEquippedArtefactStacks(player, type);
 *   if (!stacks.isEmpty() &amp;&amp; player.getHeldItemOffhand().isEmpty()) {
 *       ItemStack stack = stacks.get(slotIndex);
 *       player.setHeldItem(OFF_HAND, stack);
 *       ASBaublesIntegration.setArtefactToSlot(player, ItemStack.EMPTY, type);    // always slot 0
 *       Minecraft.getMinecraft().playerController.processRightClick(player, player.world, OFF_HAND);
 *       player.swingArm(OFF_HAND);
 *       ASBaublesIntegration.setArtefactToSlot(player, stack, type);              // always slot 0
 *       player.setHeldItem(OFF_HAND, ItemStack.EMPTY);
 *   }
 * </pre>
 *
 * <ol>
 *   <li>{@code Minecraft} is a client class. On a dedicated server the line throws
 *       {@code NoClassDefFoundError: net/minecraft/client/Minecraft}: 24 times on prod between 4 and 12
 *       October. It throws after the artefact has been moved to the off hand and its bauble slot
 *       emptied, so each failure leaves the artefact in the player's off hand.</li>
 *   <li>{@code stacks} lists only the slots that hold an artefact, but both writes go to the
 *       type's first slot. With two rings, activating the second one empties the first ring's slot
 *       and later writes the second ring into it, so the first ring is deleted and the second is in
 *       two slots. On prod, where the method stops at the crash, it deletes the first ring and leaves
 *       the second both in the off hand and in its own slot. With one ring in the second slot,
 *       "ring 1" activates it and it ends up in both slots.</li>
 *   <li>{@code stacks.get(1)} throws when only one ring is worn.</li>
 * </ol>
 *
 * <h2>The fix</h2>
 *
 * <p>{@code castItem} is replaced. It picks the same artefact, counting the same way, but remembers the
 * bauble slot it came from. The right-click goes through the server's
 * {@code PlayerInteractionManager.processRightClick}, which is what the client's
 * {@code processRightClick} asks the server to run, and the artefact goes back into the slot it came
 * from, in a {@code finally}. If that slot will not take it back, it goes into the inventory, or is
 * dropped at the player's feet.
 *
 * <p>Applies wherever the packet is handled. Its own config file, {@code mixins.mtmixins.asbauble.json},
 * so it can be blacklisted in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Pseudo
@Mixin(targets = "com.windanesz.ancientspellcraft.packet.PacketActivateBauble", remap = false)
public abstract class MixinPacketActivateBauble {

    @Inject(method = "castItem", at = @At("HEAD"), cancellable = true)
    private void mtmixins$castOnServer(ItemArtefact.Type type, EntityPlayerMP player, int slotIndex,
                                       CallbackInfo ci) {
        ci.cancel();
        BaubleType baubleType = mtmixins$baubleType(type);
        IBaublesItemHandler baubles = BaublesApi.getBaublesHandler(player);
        if (baubleType == null || baubles == null || !player.getHeldItemOffhand().isEmpty()) {
            return;
        }

        // The slotIndex-th slot of this type that holds an artefact: how getEquippedArtefactStacks counts.
        int slot = -1;
        int seen = 0;
        for (int candidate : baubleType.getValidSlots()) {
            if (baubles.getStackInSlot(candidate).getItem() instanceof ItemArtefact && seen++ == slotIndex) {
                slot = candidate;
                break;
            }
        }
        if (slot < 0) {
            return;
        }

        ItemStack artefact = baubles.getStackInSlot(slot);
        player.setHeldItem(EnumHand.OFF_HAND, artefact);
        baubles.setStackInSlot(slot, ItemStack.EMPTY);
        try {
            player.interactionManager.processRightClick(player, player.world, artefact, EnumHand.OFF_HAND);
            player.swingArm(EnumHand.OFF_HAND);
        } finally {
            // processRightClick puts any replacement stack in the off hand, so what is there now is the
            // artefact after use.
            ItemStack after = player.getHeldItemOffhand();
            player.setHeldItem(EnumHand.OFF_HAND, ItemStack.EMPTY);
            if (!after.isEmpty()) {
                baubles.setStackInSlot(slot, after);
                if (baubles.getStackInSlot(slot) != after && !player.inventory.addItemStackToInventory(after)) {
                    player.dropItem(after, false);
                }
            }
        }
    }

    /**
     * ASBaublesIntegration's own mapping, for the two types the packet can name. Not a switch: that
     * would compile to a synthetic switch-map class, which a mixin cannot carry.
     */
    @Unique
    private static BaubleType mtmixins$baubleType(ItemArtefact.Type type) {
        if (type == ItemArtefact.Type.CHARM) {
            return BaubleType.CHARM;
        }
        if (type == ItemArtefact.Type.RING) {
            return BaubleType.RING;
        }
        return null;
    }
}
