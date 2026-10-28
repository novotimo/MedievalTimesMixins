package com.novotimo.mtmixins.mixin.minefantasy;

import java.util.ArrayList;
import minefantasy.mfr.api.mining.RandomOre;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B48 &mdash; a MineFantasy handpick's random-ore drops are copies, so drops lying together no
 * longer double each other.
 *
 * <h2>The fault</h2>
 *
 * <p>{@code RandomOre.getDroppedItems} returns the {@code ItemStack} held in MFR's table entry itself,
 * not a copy, and {@code ItemHandpick.onBlockDestroyed} spawns an {@code EntityItem} around that same
 * instance ({@code EntityItem} keeps the stack by reference). Every flux, copper or coal a handpick has
 * dropped since the server started is therefore one shared stack. When two of those items are within
 * merging distance, {@code EntityItem.combineItems} grows one by the other &mdash; the same object
 * &mdash; so the count doubles, and every further drop that merges doubles it again, up to 64. Breaking
 * blocks in one spot puts every drop on the same point, which is how a placed chiselled stone turned
 * into "a pile of extra items" (Tim, 28 October). The inflated count also stays in MFR's table, and the
 * handpick's {@code setCount} on a later drop rewrites the stack every item on the ground still shows.
 *
 * <h2>The fix</h2>
 *
 * <p>The returned list is replaced element by element with copies before the handpick sees it. The roll,
 * the chances and the handpick's own count adjustment are unchanged; it now adjusts a stack of its own.
 * {@code getDroppedItems} has one caller, the handpick (checked across the MFR jar). Picking up a drop
 * restores its count ({@code onCollideWithPlayer} puts it back after the inventory empties it), which is
 * why the table survived ordinary pickups and only merging inflated it.
 *
 * <p>Server only: the handpick drops only on the server. MFR is not obfuscated, so the method is named
 * as it is with {@code remap = false}. Lives in {@code mixins.mtmixins.handpick.json}, so it can be
 * switched off in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(value = RandomOre.class, remap = false)
public abstract class MixinRandomOreCopy {

    @Inject(method = "getDroppedItems", at = @At("RETURN"), remap = false)
    private static void mtmixins$copyDrops(CallbackInfoReturnable<ArrayList<ItemStack>> cir) {
        ArrayList<ItemStack> loot = cir.getReturnValue();
        for (int i = 0; i < loot.size(); i++) {
            loot.set(i, loot.get(i).copy());
        }
    }
}
