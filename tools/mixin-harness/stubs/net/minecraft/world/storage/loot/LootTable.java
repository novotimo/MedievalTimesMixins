package net.minecraft.world.storage.loot;

import java.util.*;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Stand-in for LootTable.fillInventory: roll the loot, collect the empty slots in random order, hand both
 * to shuffleItems, then place stacks until slots run out (warning once if they do). Only the shapes the
 * mixins target matter: the shuffleItems(List, int, Random) call and the Logger.warn(String) call.
 */
public class LootTable {
    private static final Logger LOGGER = LogManager.getLogger("LootTable");
    public static final LootTable EMPTY_LOOT_TABLE = new LootTable(new ArrayList<ItemStack>());

    private final List<ItemStack> loot;

    public LootTable(List<ItemStack> loot) { this.loot = loot; }

    public List<ItemStack> generateLootForPools(Random rand, LootContext context) {
        List<ItemStack> rolled = new ArrayList<ItemStack>();
        for (ItemStack s : loot) rolled.add(s.copy());
        return rolled;
    }

    public void fillInventory(IInventory inventory, Random rand, LootContext context) {
        List<ItemStack> stacks = generateLootForPools(rand, context);
        List<Integer> free = new ArrayList<Integer>();
        for (int i = 0; i < inventory.getSizeInventory(); i++) if (inventory.getStackInSlot(i).isEmpty()) free.add(i);
        Collections.shuffle(free, rand);
        shuffleItems(stacks, free.size(), rand);
        for (ItemStack stack : stacks) {
            if (free.isEmpty()) {
                LOGGER.warn("Tried to over-fill a container");
                return;
            }
            inventory.setInventorySlotContents(free.remove(free.size() - 1), stack);
        }
    }

    /** Replaced by register B20's @Redirect in every build this harness tests, so the body never runs. */
    private void shuffleItems(List<ItemStack> stacks, int emptySlots, Random rand) {
        throw new AssertionError("B20 redirect did not apply");
    }
}
