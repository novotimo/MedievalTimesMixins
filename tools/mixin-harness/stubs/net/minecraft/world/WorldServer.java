package net.minecraft.world;

import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Stand-in for WorldServer's entity loading. Makes the same three decisions vanilla 1.12.2 makes when a
 * chunk hands it entities: refuse a dead entity; for a UUID it already holds, take the new one only if
 * the old one is queued for unload; otherwise refuse it and leave it in the chunk.
 */
public class WorldServer extends World {
    public final Map<UUID, Entity> entitiesByUuid = new HashMap<UUID, Entity>();
    public final List<String> vanillaWarnings = new ArrayList<String>();
    private final ChunkProviderServer chunkProvider = new ChunkProviderServer();

    public ChunkProviderServer getChunkProvider() { return chunkProvider; }
    public Entity getEntityFromUuid(UUID uuid) { return entitiesByUuid.get(uuid); }
    public void queueUnload(Entity e) { unloadedEntityList.add(e); }

    public void loadEntities(Collection<Entity> entityCollection) {
        for (Entity incoming : new ArrayList<Entity>(entityCollection)) {
            if (accepts(incoming)) {
                loadedEntityList.add(incoming);
                entitiesByUuid.put(incoming.getUniqueID(), incoming);
            }
        }
    }

    private boolean accepts(Entity incoming) {
        if (incoming.isDead) {
            vanillaWarnings.add("Tried to add entity " + EntityList.getKey(incoming) + " but it was marked as removed already");
            return false;
        }
        Entity existing = entitiesByUuid.get(incoming.getUniqueID());
        if (existing == null) return true;
        if (!unloadedEntityList.remove(existing)) {
            vanillaWarnings.add("Keeping entity " + EntityList.getKey(existing) + " that already exists with UUID " + incoming.getUniqueID());
            return false;
        }
        removeEntityDangerously(existing);
        return true;
    }

    public void removeEntityDangerously(Entity e) { loadedEntityList.remove(e); entitiesByUuid.remove(e.getUniqueID()); }
}
