package tests;
import java.util.*;
import com.novotimo.mtmixins.util.EntityCollisions;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.world.World;
public class CollisionTest {
    static void tick(World w) { w.totalTime++; for (Entity e : new ArrayList<Entity>(w.loadedEntityList)) e.onUpdate(); }
    public static void run() {
        World w = new World() {};
        EntityCow cow = new EntityCow(w); EntityMinecart cart = new EntityMinecart(w);
        w.loadedEntityList.add(cow); w.loadedEntityList.add(cart);
        for (int t = 0; t < 20; t++) tick(w);
        System.out.println("[B18] cow walking into a stationary minecart for 20 ticks -> minecart pushed " + cart.pushesReceived + " times (expected 20)");
        System.out.println("[B18] minecart: " + (cart.pushesReceived == 20 ? "PASS" : "FAIL"));

        World pen = new World() {};
        List<EntityCow> cows = new ArrayList<EntityCow>();
        for (int i = 0; i < 40; i++) { EntityCow c = new EntityCow(pen); cows.add(c); pen.loadedEntityList.add(c); }
        int worstPerTick = 0, maxPushesPerTick = 0, minPushesPerTick = Integer.MAX_VALUE;
        for (int t = 0; t < 5; t++) {
            int total0 = 0; for (EntityCow c : cows) total0 += c.pushesReceived;
            tick(pen);
            int total1 = 0; for (EntityCow c : cows) total1 += c.pushesReceived;
            int pushes = total1 - total0;
            maxPushesPerTick = Math.max(maxPushesPerTick, pushes); minPushesPerTick = Math.min(minPushesPerTick, pushes);
            for (EntityCow c : cows) worstPerTick = Math.max(worstPerTick, ((EntityCollisions.Counted) (Object) c).mtmixins$getCollisionsThisTick());
        }
        System.out.println("[B18] 40 cows in one pen, cap " + EntityCollisions.cap() + ": pushes per tick min " + minPushesPerTick + " max " + maxPushesPerTick
                + " (vanilla would be 1560; cap bound is 40*8/2=160), highest per-entity count read back this tick " + worstPerTick);
        System.out.println("[B18] pen: " + (maxPushesPerTick > 0 && maxPushesPerTick <= 160 && worstPerTick <= 8 ? "PASS" : "FAIL"));
    }
}
