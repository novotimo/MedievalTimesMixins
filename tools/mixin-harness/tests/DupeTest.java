package tests;
import java.util.*;
import com.google.common.collect.ImmutableList;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
public class DupeTest {
    static EntityCow cow(World w, UUID u, double x, double y, double z) { EntityCow c = new EntityCow(w); c.setUniqueId(u); c.posX = x; c.posY = y; c.posZ = z; return c; }
    static Chunk chunk(WorldServer ws) { Chunk c = new Chunk(); ws.getChunkProvider().chunks.put(World.key(0, 0), c); return c; }
    static void load(WorldServer ws, Chunk c) { ws.loadEntities(ImmutableList.copyOf(c.entities)); }
    static boolean keeping(WorldServer ws) { for (String s : ws.vanillaWarnings) if (s.startsWith("Keeping entity")) return true; return false; }
    static void report(String name, boolean ok, String detail) { System.out.println("[B15] " + name + ": " + (ok ? "PASS" : "FAIL") + " - " + detail); }
    public static void run() {
        UUID u = UUID.randomUUID();
        // 1. Unload race with two copies of the UUID in the reloaded slice.
        WorldServer ws = new WorldServer();
        EntityCow old = cow(ws, u, 0.5, 64, 0.5); ws.loadedEntityList.add(old); ws.entitiesByUuid.put(u, old); ws.queueUnload(old);
        Chunk ch = chunk(ws); EntityCow c1 = cow(ws, u, 0.5, 64, 0.5), c2 = cow(ws, u, 0.5, 64, 0.5); ch.entities.add(c1); ch.entities.add(c2);
        load(ws, ch);
        report("unload race + 2 copies in slice", ch.entities.size() == 1 && ch.entities.get(0) == c1 && c2.isDead && ws.getEntityFromUuid(u) == c1 && !keeping(ws),
                "chunk list now " + ch.entities.size() + " entities, world holds c1=" + (ws.getEntityFromUuid(u) == c1) + ", vanilla warnings " + ws.vanillaWarnings);
        // 2. Plain unload race, one copy: vanilla swap, nothing dropped (regression check).
        ws = new WorldServer(); old = cow(ws, u, 0.5, 64, 0.5); ws.loadedEntityList.add(old); ws.entitiesByUuid.put(u, old); ws.queueUnload(old);
        ch = chunk(ws); c1 = cow(ws, u, 0.5, 64, 0.5); ch.entities.add(c1); load(ws, ch);
        report("plain unload race", ch.entities.size() == 1 && !c1.isDead && ws.getEntityFromUuid(u) == c1 && !ws.loadedEntityList.contains(old) && ws.vanillaWarnings.isEmpty(),
                "c1 swapped in=" + (ws.getEntityFromUuid(u) == c1) + ", warnings " + ws.vanillaWarnings);
        // 3. Live (not queued) copy nearby: incoming dropped and pruned (regression check).
        ws = new WorldServer(); old = cow(ws, u, 0.5, 64, 0.5); ws.loadedEntityList.add(old); ws.entitiesByUuid.put(u, old);
        ch = chunk(ws); c1 = cow(ws, u, 1.5, 64, 0.5); ch.entities.add(c1); load(ws, ch);
        report("live near duplicate", ch.entities.isEmpty() && c1.isDead && ws.getEntityFromUuid(u) == old, "chunk list " + ch.entities.size());
        // 4. Live copy far away: incoming re-identified, both kept (regression check).
        ws = new WorldServer(); old = cow(ws, u, 0.5, 64, 0.5); ws.loadedEntityList.add(old); ws.entitiesByUuid.put(u, old);
        ch = chunk(ws); c1 = cow(ws, u, 900.5, 64, 0.5); ch.entities.add(c1); load(ws, ch);
        report("live far duplicate", !c1.isDead && !u.equals(c1.getUniqueID()) && ws.loadedEntityList.contains(c1) && ws.loadedEntityList.contains(old), "c1 new uuid=" + !u.equals(c1.getUniqueID()));
        // 5. Three copies in one slice, no world copy: copies 2 and 3 dropped (regression check).
        ws = new WorldServer(); ch = chunk(ws); c1 = cow(ws, u, 0.5, 64, 0.5); c2 = cow(ws, u, 0.5, 64, 0.5); EntityCow c3 = cow(ws, u, 0.5, 64, 0.5);
        ch.entities.add(c1); ch.entities.add(c2); ch.entities.add(c3); load(ws, ch);
        report("3 copies in slice, no race", ch.entities.size() == 1 && c2.isDead && c3.isDead && !keeping(ws), "chunk list " + ch.entities.size());
    }
}
