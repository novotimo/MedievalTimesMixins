package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.MedievalTimesMixins;
import com.novotimo.mtmixins.mixin.minefantasy.AccessorContainerCrucible;
import com.novotimo.mtmixins.mixin.minefantasy.AccessorTileEntityCrucible;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;
import minefantasy.mfr.container.ContainerCrucible;
import minefantasy.mfr.mechanics.knowledge.ResearchLogic;
import minefantasy.mfr.registry.recipe.AlloyRecipeBase;
import minefantasy.mfr.registry.recipe.CraftingManagerAlloy;
import minefantasy.mfr.registry.recipe.CrucibleCraftMatrix;
import minefantasy.mfr.tile.TileEntityCrucible;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.items.ItemStackHandler;

/**
 * Register B43 &mdash; whose research a MineFantasy crucible smelts with. Shared by
 * {@code mixin/minefantasy/MixinBlockCrucibleResearch} and
 * {@code mixin/vanilla/MixinNetHandlerPlayServerCrucibleLoad}. Everything here runs on the server thread.
 *
 * <p>A crucible keeps a list of alloy researches and only smelts alloys on it. MFR replaced that list with
 * the research of whoever last right-clicked the crucible, so a less researched player opening a town
 * smithy stopped the batch in it until its owner clicked it again. The list now belongs to the players who
 * <b>load</b> the batch:
 *
 * <ul>
 *   <li>a player putting items into an <i>empty</i> crucible starts a new batch, and the list becomes their
 *       research;</li>
 *   <li>a player adding to a crucible that already has inputs adds their research to the list; nothing
 *       is ever removed mid-batch;</li>
 *   <li>a creative player loading an empty crucible gets the whole tree (MFR's research check passes
 *       everything in creative) for that batch only, and a creative player topping up someone else's
 *       batch adds nothing, so an admin cannot upgrade another player's crucible;</li>
 *   <li>right-clicking, opening the window, taking bars out with a mould and hoppers leave the list
 *       alone.</li>
 * </ul>
 *
 * <p>A survival player may not open the window while the inputs make an alloy they have not researched,
 * so they cannot take another player's materials out from under a running batch. A survival player who
 * opens one whose inputs make an alloy they <i>have</i> researched adds that one research if the list
 * lacks it. That repairs lists MFR wrote before this register, and lets the owner of a hopper-fed crucible
 * start it by opening it, without granting anything beyond the alloy already loaded.
 *
 * <p>Fails open: if anything here throws, the window opens and the list is left as it was, and the
 * failure is logged once.
 */
public final class CrucibleResearch {

    /** Slots 0 to 8 are the 3x3 inputs; slot 9 is the output. */
    private static final int INPUT_SLOTS = 9;

    private static boolean warned;

    private CrucibleResearch() {
    }

    /**
     * Whether {@code player} may open {@code crucible}'s window. Refuses, with a message, a survival player
     * whose research does not cover the alloy the inputs make; otherwise adds that alloy's research to the
     * crucible's list if it is missing.
     */
    public static boolean mayOpen(TileEntityCrucible crucible, EntityPlayer player) {
        try {
            if (player.capabilities.isCreativeMode) {
                return true;
            }

            AlloyRecipeBase loaded = loadedRecipe(crucible);
            if (loaded == null || "none".equals(loaded.getRequiredResearch())) {
                return true;
            }

            String research = loaded.getRequiredResearch();
            if (!knows(player, research)) {
                player.sendStatusMessage(new TextComponentString(TextFormatting.GOLD
                        + "This crucible is smelting an alloy you have not researched."), true);
                return false;
            }

            Set<String> list = ((AccessorTileEntityCrucible) crucible).mtmixins$knownResearches();
            if (list == null || !list.contains(research)) {
                Set<String> healed = list == null ? new HashSet<String>() : new HashSet<String>(list);
                healed.add(research);
                crucible.setKnownResearches(healed);
                crucible.markDirty();
            }
            return true;
        } catch (Throwable t) {
            warnOnce(t);
            return true;
        }
    }

    /**
     * The crucible and a copy of its inputs if {@code container} is a crucible window, else null. Taken
     * before a slot click is processed and handed back to {@link #afterClick}.
     */
    @Nullable
    public static Object beforeClick(Container container) {
        try {
            if (!(container instanceof ContainerCrucible)) {
                return null;
            }
            TileEntityCrucible crucible = ((AccessorContainerCrucible) container).mtmixins$tile();
            return crucible == null ? null : new Inputs(crucible);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /** Updates the list if {@code player}'s slot click put anything into the inputs. */
    public static void afterClick(Object before, EntityPlayer player) {
        try {
            Inputs inputs = (Inputs) before;
            if (inputs.added()) {
                loaded(inputs.crucible, player, inputs.wereEmpty());
            }
        } catch (Throwable t) {
            warnOnce(t);
        }
    }

    private static void loaded(TileEntityCrucible crucible, EntityPlayer player, boolean wasEmpty) {
        if (!wasEmpty && player.capabilities.isCreativeMode) {
            return;
        }

        Set<String> list = researchOf(player);
        if (!wasEmpty) {
            Set<String> current = ((AccessorTileEntityCrucible) crucible).mtmixins$knownResearches();
            if (current != null) {
                list.addAll(current);
            }
        }
        crucible.setKnownResearches(list);
        crucible.markDirty();
    }

    /**
     * The alloy the inputs make in this crucible, ignoring research: the first matching recipe, as MFR's
     * {@code CraftingManagerAlloy.findMatchingRecipe} takes it, or null if none matches or the crucible's
     * tier is too low for it to smelt. Fills the crucible's own matrix exactly as its {@code getResult}
     * does every tick.
     */
    @Nullable
    private static AlloyRecipeBase loadedRecipe(TileEntityCrucible crucible) {
        CrucibleCraftMatrix matrix = ((AccessorTileEntityCrucible) crucible).mtmixins$craftMatrix();
        if (matrix == null) {
            return null;
        }

        ItemStackHandler inventory = crucible.getInventory();
        boolean any = false;
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            matrix.setInventorySlotContents(slot, stack);
            any |= !stack.isEmpty();
        }
        if (!any) {
            return null;
        }

        for (AlloyRecipeBase recipe : CraftingManagerAlloy.getRecipes()) {
            if (recipe.matches(matrix)) {
                return recipe.getTier() > crucible.getTier() ? null : recipe;
            }
        }
        return null;
    }

    /** The alloy researches {@code player} has, built the way MFR's {@code BlockCrucible} builds them. */
    private static Set<String> researchOf(EntityPlayer player) {
        Set<String> researches = new HashSet<String>();
        for (String research : CraftingManagerAlloy.getAlloyResearches()) {
            if (knows(player, research)) {
                researches.add(research);
            }
        }
        return researches;
    }

    private static boolean knows(EntityPlayer player, String research) {
        return ResearchLogic.getResearchCheck(player, ResearchLogic.getResearch(research));
    }

    private static void warnOnce(Throwable t) {
        if (!warned) {
            warned = true;
            MedievalTimesMixins.LOG.warn("Register B43 failed; crucibles fall back to opening normally and "
                    + "keeping their research list. Reported once.", t);
        }
    }

    /** A crucible's inputs as they were before a slot click. */
    private static final class Inputs {

        final TileEntityCrucible crucible;
        private final ItemStack[] before = new ItemStack[INPUT_SLOTS];

        Inputs(TileEntityCrucible crucible) {
            this.crucible = crucible;
            ItemStackHandler inventory = crucible.getInventory();
            for (int slot = 0; slot < INPUT_SLOTS; slot++) {
                before[slot] = inventory.getStackInSlot(slot).copy();
            }
        }

        boolean wereEmpty() {
            for (ItemStack stack : before) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /** Whether any input slot now holds something new, or more of what it held. */
        boolean added() {
            ItemStackHandler inventory = crucible.getInventory();
            for (int slot = 0; slot < INPUT_SLOTS; slot++) {
                ItemStack now = inventory.getStackInSlot(slot);
                ItemStack was = before[slot];
                if (now.isEmpty()) {
                    continue;
                }
                if (was.isEmpty() || !ItemStack.areItemsEqual(was, now) || !ItemStack.areItemStackTagsEqual(was, now)
                        || now.getCount() > was.getCount()) {
                    return true;
                }
            }
            return false;
        }
    }
}
