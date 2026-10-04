package com.novotimo.mtmixins;

/**
 * Lets a {@code LootTable} say which table it is.
 *
 * <p>Vanilla's {@code LootTable} does not know its own name &mdash; the name lives in
 * {@code LootTableManager}'s cache key and is thrown away by the time anything is being filled,
 * which is why "Tried to over-fill a container" has never said what over-filled. The manager mixin
 * stamps the name on the table as it hands it out, and the attribution mixin reads it back.
 *
 * <p>Plain interface in the mod's own package rather than in the mixin package, because a mixin can
 * only add an interface to a target class if the interface is a real class in the jar.
 */
public interface INamedLootTable {

    String mtmixins$lootTableName();

    void mtmixins$setLootTableName(String name);
}
