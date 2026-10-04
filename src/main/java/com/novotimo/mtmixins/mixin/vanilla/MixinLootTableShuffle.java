package com.novotimo.mtmixins.mixin.vanilla;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.item.ItemStack;
import net.minecraft.world.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B20 &mdash; stops "Tried to over-fill a container", which is a vanilla bug and not a
 * loot-table problem.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code LootTable.shuffleItems} spreads multi-item stacks across spare slots. It pulls every
 * stack with a count above 1 into a side list, works out how many slots are spare, and then:
 *
 * <pre>
 *   p_186463_2_ = p_186463_2_ - stacks.size();
 *
 *   while (p_186463_2_ &gt; 0 &amp;&amp; !list.isEmpty())
 *   {
 *       ... split one stack in two, put each half in list or stacks ...
 *   }               // &lt;-- p_186463_2_ is never decremented
 *
 *   stacks.addAll(list);
 * </pre>
 *
 * <p><b>The loop never decrements its slot counter.</b> So it does not stop when the slots are full;
 * it stops when the side list happens to empty, which is decided by coin flips. A stack of 20 items
 * can be split into as many as 20 stacks. Then {@code fillInventory} walks the result, runs out of
 * slots, logs the warning and <b>returns, discarding everything left</b>.
 *
 * <p>This is why the warning never correlated with anything we measured. It does not depend on how
 * many stacks a table rolls, nor on the container's size, nor on anything being filled twice &mdash;
 * it depends on <b>how many items are inside each stack</b>. Four tables were caught doing it on this
 * server into inventories that were <i>completely empty</i>:
 * {@code iceandfire:fire_dragon_female_cave} (14 times), {@code minecraft:chests/simple_dungeon}
 * (10), {@code ebwizardry:chests/library_ruins_bookshelf} into a 12-slot bookshelf (5), and
 * {@code iceandfire:fire_dragon_male_cave} (1). None of them rolls more than 9 stacks.
 *
 * <p>It also means raising every {@code setCount} range by a quarter, which is what the loot scripts
 * now do, makes this strictly more likely rather than less. The two were working against each other.
 *
 * <h2>The fix</h2>
 *
 * <p>Replaces the shuffle step with one that keeps the same intent and does the accounting:
 *
 * <ol>
 *   <li><b>Merge first.</b> Identical stacks are combined up to their max stack size. This is
 *       lossless and is what brings the count under the limit in almost every case.</li>
 *   <li><b>Then spread, with a budget.</b> Split large stacks to fill spare slots, stopping when the
 *       slots are accounted for &mdash; the decrement vanilla is missing.</li>
 *   <li><b>Never discard.</b> If a table genuinely produces more distinct items than the container
 *       has slots, that is a real conflict and vanilla's behaviour of dropping the tail stands, but
 *       it is now reported with the numbers instead of a bare warning.</li>
 * </ol>
 *
 * <p>Redirecting the one call rather than overwriting {@code shuffleItems} keeps the rest of
 * {@code fillInventory} vanilla, and leaves the method intact for any other mod that calls it.
 */
@Mixin(LootTable.class)
public abstract class MixinLootTableShuffle {

    /**
     * How full a container should end up, as a share of its slots, drawn fresh for every fill.
     *
     * <p>Vanilla's intent was to spread loot across every spare slot, and the corrected version did
     * exactly that &mdash; which put 27 stacks in a 27-slot chest and made every chest look the
     * same. The brief is roughly half full on average, varying chest to chest, with the items
     * concentrated into fewer and larger stacks.
     *
     * <p>0.40 to 0.65 of capacity, uniform, so a 27-slot chest lands between 11 and 18 slots with a
     * mean near 14, and a 12-slot bookshelf scales to the same proportion rather than being given a
     * chest's numbers. The draw is per container, so two chests from one table do not look alike.
     */
    @Unique
    private static final double FILL_MIN = 0.40D;

    @Unique
    private static final double FILL_MAX = 0.65D;

    @Unique
    private static long mtmixins$fills = 0L;

    @Unique
    private static long mtmixins$slotsUsed = 0L;

    @Unique
    private static long mtmixins$wouldHaveOverfilled = 0L;

    @Redirect(
            method = "fillInventory",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/storage/loot/LootTable;shuffleItems"
                            + "(Ljava/util/List;ILjava/util/Random;)V"))
    private void mtmixins$shuffleWithoutOverflowing(LootTable self, List<ItemStack> stacks,
                                                    int emptySlots, Random rand) {
        // 1. drop the empties, as vanilla does
        Iterator<ItemStack> it = stacks.iterator();
        while (it.hasNext()) {
            if (it.next().isEmpty()) {
                it.remove();
            }
        }
        if (emptySlots <= 0) {
            return;
        }

        int before = stacks.size();

        // 2. merge identical stacks up to their max size - lossless, and usually enough on its own
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack a = stacks.get(i);
            if (a.isEmpty()) {
                continue;
            }
            for (int j = stacks.size() - 1; j > i; j--) {
                ItemStack b = stacks.get(j);
                if (b.isEmpty() || a.getCount() >= a.getMaxStackSize()) {
                    continue;
                }
                // areCapsCompatible as well as the item and the NBT: two stacks of the same item
                // with different Forge capability data are not interchangeable, and merging them
                // would quietly destroy one side's caps. Relevant here because this pack's loot
                // carries capability data on a lot of items - every chest item in the structure
                // files has a customnpcs:itemscripteddata cap attached.
                if (ItemStack.areItemsEqual(a, b) && ItemStack.areItemStackTagsEqual(a, b)
                        && a.areCapsCompatible(b)) {
                    int room = a.getMaxStackSize() - a.getCount();
                    int move = Math.min(room, b.getCount());
                    if (move > 0) {
                        a.grow(move);
                        b.shrink(move);
                        if (b.isEmpty()) {
                            stacks.remove(j);
                        }
                    }
                }
            }
        }

        // 3. spread toward a randomised target occupancy rather than into every spare slot
        int target = (int) Math.round(emptySlots * (FILL_MIN + rand.nextDouble() * (FILL_MAX - FILL_MIN)));
        if (target < 1) {
            target = 1;
        }
        if (target > emptySlots) {
            target = emptySlots;
        }
        // Merging may already have left more stacks than the target. That is fine and is not undone
        // - the goal is fewer, fuller stacks, so nothing here ever merges less or splits more than
        // it has to.
        int budget = target - stacks.size();
        while (budget > 0) {
            int biggest = -1;
            for (int i = 0; i < stacks.size(); i++) {
                if (stacks.get(i).getCount() > 1
                        && (biggest < 0 || stacks.get(i).getCount() > stacks.get(biggest).getCount())) {
                    biggest = i;
                }
            }
            if (biggest < 0) {
                break;                      // nothing left worth splitting
            }
            ItemStack big = stacks.get(biggest);
            int take = Math.max(1, rand.nextInt(Math.max(1, big.getCount() / 2)) + 1);
            if (take >= big.getCount()) {
                break;
            }
            stacks.add(big.splitStack(take));
            budget--;
        }

        if (stacks.size() > emptySlots) {
            // A real conflict: more distinct items than slots. Vanilla would log a bare line and
            // drop the tail; at least say what the numbers were.
            MedievalTimesMixins.LOG.warn(
                    "Loot genuinely does not fit: {} distinct stacks for {} slots after merging "
                            + "(was {} before). The tail will be dropped. Register B20.",
                    stacks.size(), emptySlots, before);
        }

        // Running average of how full containers actually end up, so the target can be checked
        // against reality instead of taken on trust.
        //
        // The previous version counted "prevented an over-fill" as before > emptySlots, which could
        // never be true: the overflow was created by vanilla's unbounded splitting, not by the
        // number of stacks the table rolled, so the pre-split count was always comfortably under
        // the slot count. It reported zero on a run where it had in fact prevented thirty.
        if (before > 0) {
            mtmixins$fills++;
            mtmixins$slotsUsed += stacks.size();
            if (emptySlots - stacks.size() < 1) {
                mtmixins$wouldHaveOverfilled++;
            }
            if (mtmixins$fills == 1L || mtmixins$fills % 100L == 0L) {
                MedievalTimesMixins.LOG.info(
                        "Loot fill: {} stacks into {} slots (target was {}). Average occupancy over "
                                + "{} fills: {} slots, {}% of capacity. Register B20.",
                        stacks.size(), emptySlots, target, mtmixins$fills,
                        String.format("%.1f", mtmixins$slotsUsed / (double) mtmixins$fills),
                        String.format("%.0f", 100.0D * mtmixins$slotsUsed
                                / (double) mtmixins$fills / Math.max(1, emptySlots)));
            }
        }

        Collections.shuffle(stacks, rand);
    }
}
