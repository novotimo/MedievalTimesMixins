package com.novotimo.mtmixins.mixin.ancientwarfare;

import java.io.PrintStream;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.entity.EntityLiving;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.shadowmage.ancientwarfare.core.config.AWCoreStatics;
import net.shadowmage.ancientwarfare.core.owner.Owner;
import net.shadowmage.ancientwarfare.npc.entity.NpcBase;
import net.shadowmage.ancientwarfare.npc.entity.NpcCombat;
import net.shadowmage.ancientwarfare.npc.item.ItemCommandBaton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B23 &mdash; Ancient Warfare combat NPCs pick up whatever they walk over, then complain about
 * it several times a second for as long as they are loaded.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code NpcCombat} turns on vanilla loot pickup ({@code setCanPickUpLoot(true)}) and overrides
 * {@code canEquipItem} as
 *
 * <pre>
 *   return getHeldItemMainhand().isEmpty() || getHeldItemMainhand().getItem() == stack.getItem();
 * </pre>
 *
 * <p>Vanilla's {@code updateEquipmentIfNeeded} equips anything into an empty slot that
 * {@code canEquipItem} accepts, and every non-armour item goes to the main hand. So a combat NPC with
 * an empty hand picks up the first item it walks over &mdash; in practice the bones and rotten flesh
 * dropped by the mobs it has just killed &mdash; and holds it from then on.
 *
 * <p>Its role comes from that item: {@code getSubtypeFromEquipment} makes it a medic, engineer,
 * archer, commander or soldier, and for anything else prints
 *
 * <pre>
 *   System.out.println("[AW2t] WARNING: item does not match any Combat NPC subtype! item=" + item);
 * </pre>
 *
 * <p>on every call. The medic AI asks for the subtype every few ticks, so each such NPC adds several
 * lines a second, each paying for FML's stack walk to name the caller: 76,656 lines in the
 * 2026-10-04..09 logs, 53,327 of them in four hours on 10-09.
 *
 * <h2>The fix</h2>
 *
 * <ol>
 *   <li><b>Pickup.</b> An empty-handed combat NPC refuses a main-hand item that would give it no
 *       role, judged by the same five tests {@code getSubtypeFromEquipment} uses. It still picks up
 *       weapons, bows, hammers, medic items and batons, and armour and shields go through
 *       Ancient Warfare's own rule unchanged.</li>
 *   <li><b>The print.</b> NPCs that already hold such an item are reported once per NPC per boot,
 *       with owner, position and the item's registry name, instead of every few ticks. The item is
 *       left where it is; it can be taken off through the NPC's inventory.</li>
 * </ol>
 *
 * <p>Both halves sit on the logical server; the client never picks anything up and has the print
 * dropped silently. Its own config file, {@code mixins.mtmixins.awcombat.json}, so it can be
 * blacklisted in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(value = NpcCombat.class, remap = false)
public abstract class MixinNpcCombatEquipment {

    /** NPCs already reported this boot. */
    @Unique
    private static final Set<UUID> mtmixins$reported = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // canEquipItem is inherited from vanilla, so it is SRG-named in production while this mixin has
    // remap = false: list both names (see "The invoke-owner trap" in the README).
    @Inject(method = {"canEquipItem", "func_175448_a"}, at = @At("HEAD"), cancellable = true)
    private void mtmixins$refuseItemsWithNoRole(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        EntityLiving self = (EntityLiving) (Object) this;
        if (EntityLiving.getSlotForItemStack(stack) == EntityEquipmentSlot.MAINHAND
                && self.getHeldItemMainhand().isEmpty()
                && !mtmixins$givesCombatRole(stack)) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(method = "getSubtypeFromEquipment",
            at = @At(value = "INVOKE", target = "Ljava/io/PrintStream;println(Ljava/lang/String;)V"))
    private void mtmixins$reportOncePerNpc(PrintStream out, String message) {
        NpcBase self = (NpcBase) (Object) this;
        if (self.world.isRemote || !mtmixins$reported.add(self.getUniqueID())) {
            return;
        }
        // An unowned NPC has an Owner with an empty name rather than no Owner.
        Owner owner = self.getOwner();
        String ownerName = owner == null || owner.getName() == null || owner.getName().isEmpty()
                ? "nobody" : owner.getName();
        MedievalTimesMixins.LOG.info(
                "Ancient Warfare combat NPC {} owned by {} at {}, {}, {} in dim {} is holding {}, which "
                        + "gives it no combat role. Ancient Warfare prints that several times a second; "
                        + "it is reported once per NPC per boot. Register B23.",
                self.getUniqueID(), ownerName,
                (int) Math.floor(self.posX), (int) Math.floor(self.posY), (int) Math.floor(self.posZ),
                self.dimension, self.getHeldItemMainhand().getItem().getRegistryName());
    }

    /** The five tests of {@code NpcCombat.getSubtypeFromEquipment}, in the same order. */
    @Unique
    private static boolean mtmixins$givesCombatRole(ItemStack stack) {
        Item item = stack.getItem();
        return AWCoreStatics.medicItems.contains(item.getRegistryName())
                || item.getToolClasses(stack).contains("hammer")
                || item instanceof ItemBow
                || item instanceof ItemCommandBaton
                || item.isEnchantable(stack);
    }
}
