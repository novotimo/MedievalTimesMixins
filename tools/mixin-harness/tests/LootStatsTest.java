package tests;
import java.util.*;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootTable;
public class LootStatsTest {
    static final class Inv implements IInventory {
        final ItemStack[] s; Inv(int n) { s = new ItemStack[n]; Arrays.fill(s, ItemStack.EMPTY); }
        public int getSizeInventory() { return s.length; } public ItemStack getStackInSlot(int i) { return s[i]; } public void setInventorySlotContents(int i, ItemStack st) { s[i] = st; }
        int used() { int u = 0; for (ItemStack x : s) if (!x.isEmpty()) u++; return u; }
    }
    public static void run() {
        List<ItemStack> loot = new ArrayList<ItemStack>();
        for (int i = 0; i < 5; i++) loot.add(new ItemStack(new Item("item" + i), 10, 64));
        LootTable table = new LootTable(loot);
        Random rand = new Random(42);
        long used = 0, offered = 0;
        for (int i = 0; i < 100; i++) {
            Inv inv = new Inv(i == 99 ? 12 : 27);
            table.fillInventory(inv, rand, new LootContext());
            used += inv.used(); offered += inv.getSizeInventory();
        }
        System.out.println("[B20] ground truth over 100 fills (99 x 27-slot chest, 1 x 12-slot bookshelf): " + used + " of " + offered + " slots = "
                + String.format("%.0f", 100.0 * used / offered) + "%. Compare with the 'Loot fill' line for fill 100 in the log.");
    }
}
