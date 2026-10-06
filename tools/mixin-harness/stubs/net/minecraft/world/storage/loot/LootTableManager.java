package net.minecraft.world.storage.loot;
import java.util.*;
import net.minecraft.util.ResourceLocation;
/** Missing tables come back as the shared LootTable.EMPTY_LOOT_TABLE, as in vanilla's Loader. */
public class LootTableManager {
    public final Map<ResourceLocation, LootTable> registered = new HashMap<ResourceLocation, LootTable>();
    public LootTable getLootTableFromLocation(ResourceLocation ressources) { LootTable t = registered.get(ressources); return t == null ? LootTable.EMPTY_LOOT_TABLE : t; }
}
