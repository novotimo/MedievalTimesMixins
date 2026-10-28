package com.novotimo.mtmixins.util;

import com.novotimo.mtmixins.bridge.TransparencyBridge;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * B52's bookkeeping: which creative-inventory changes are items taken from the creative menu.
 *
 * <p>The creative client tells the server the new contents of a slot, not where they came from. Moving an
 * item within your own inventory arrives as "slot A is now empty", then "slot B now holds it"; taking one
 * from the menu arrives as "slot B now holds it" alone. So whatever leaves a slot goes into a per-player
 * pool, and whatever arrives in a slot (or is dropped, slot -1) is paid from the pool first; only what the
 * pool cannot cover came from the menu. Pool entries expire after two minutes, longer than anyone holds an
 * item on the cursor. Items deleted in the creative bin just expire.
 *
 * <p>Server thread only (the packet handler runs there after {@code checkThreadAndEnqueue}). Items are keyed by
 * registry name, metadata and NBT, so a renamed or enchanted copy is not mistaken for a plain one.
 */
public final class CreativeTakes {

    private static final long WINDOW_MS = 120_000L;

    private static final Map<UUID, Map<String, long[]>> POOLS = new HashMap<>();

    private CreativeTakes() {
    }

    /** A creative packet set an inventory slot from {@code before} to {@code after}. */
    public static void slotSet(EntityPlayerMP player, ItemStack before, ItemStack after) {
        try {
            Map<String, long[]> pool = pool(player);
            if (!before.isEmpty() && !after.isEmpty() && key(before).equals(key(after))) {
                int delta = after.getCount() - before.getCount();
                if (delta < 0) {
                    removed(pool, key(before), -delta);
                } else if (delta > 0) {
                    added(player, pool, after, delta);
                }
                return;
            }
            if (!before.isEmpty()) {
                removed(pool, key(before), before.getCount());
            }
            if (!after.isEmpty()) {
                added(player, pool, after, after.getCount());
            }
        } catch (Throwable t) {
            // Bookkeeping only; never in the way of the packet.
        }
    }

    /** A creative packet dropped {@code stack} (slot -1). */
    public static void dropped(EntityPlayerMP player, ItemStack stack) {
        try {
            if (!stack.isEmpty()) {
                added(player, pool(player), stack, stack.getCount());
            }
        } catch (Throwable t) {
            // As above.
        }
    }

    private static Map<String, long[]> pool(EntityPlayerMP player) {
        Map<String, long[]> pool = POOLS.computeIfAbsent(player.getUniqueID(), id -> new HashMap<>());
        long now = System.currentTimeMillis();
        Iterator<long[]> it = pool.values().iterator();
        while (it.hasNext()) {
            if (now - it.next()[1] > WINDOW_MS) {
                it.remove();
            }
        }
        // Players who left creative stop sending these; drop their empty pools as we go.
        POOLS.values().removeIf(Map::isEmpty);
        return POOLS.computeIfAbsent(player.getUniqueID(), id -> pool);
    }

    private static void removed(Map<String, long[]> pool, String key, int count) {
        long[] entry = pool.computeIfAbsent(key, k -> new long[2]);
        entry[0] += count;
        entry[1] = System.currentTimeMillis();
    }

    private static void added(EntityPlayerMP player, Map<String, long[]> pool, ItemStack stack, int count) {
        String key = key(stack);
        long[] entry = pool.get(key);
        int fromPool = entry == null ? 0 : (int)Math.min(entry[0], count);
        if (entry != null) {
            entry[0] -= fromPool;
            if (entry[0] <= 0) {
                pool.remove(key);
            }
        }
        int taken = count - fromPool;
        if (taken > 0) {
            ItemStack record = stack.copy();
            record.setCount(taken);
            TransparencyBridge.creativeTake(player, record);
        }
    }

    private static String key(ItemStack stack) {
        ResourceLocation id = stack.getItem().getRegistryName();
        return id + ":" + stack.getMetadata() + (stack.hasTagCompound() ? ":" + stack.getTagCompound() : "");
    }
}
