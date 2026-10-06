package tests;
import java.util.*;
import com.novotimo.mtmixins.INamedLootTable;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraft.world.storage.loot.LootTableManager;
public class LootNameTest {
    public static void run() {
        LootTableManager m = new LootTableManager();
        LootTable chest = new LootTable(new ArrayList<ItemStack>());
        m.registered.put(new ResourceLocation("mod:chest"), chest);
        long t0 = System.nanoTime();
        for (int i = 0; i < 20000; i++) { m.getLootTableFromLocation(new ResourceLocation("mod:missing_" + (i % 5))); m.getLootTableFromLocation(new ResourceLocation("mod:chest")); }
        long ms = (System.nanoTime() - t0) / 1000000L;
        String empty = ((INamedLootTable) LootTable.EMPTY_LOOT_TABLE).mtmixins$lootTableName();
        String real = ((INamedLootTable) chest).mtmixins$lootTableName();
        System.out.println("[lootattrib] after 20000 lookups of 5 missing tables: shared empty table's name is " + empty.length() + " chars, took " + ms + " ms; starts " + empty.substring(0, Math.min(90, empty.length())));
        System.out.println("[lootattrib] real table still named: " + real);
        System.out.println("[lootattrib] " + (empty.length() < 200 && "mod:chest".equals(real) ? "PASS" : "FAIL"));
    }
}
