package net.minecraft.entity;
import java.util.UUID;
import net.minecraft.world.World;
public abstract class Entity {
    public World world; public boolean isDead; public double posX, posY, posZ; public int chunkCoordX, chunkCoordZ;
    public int pushesReceived;
    private UUID uuid = UUID.randomUUID();
    public Entity(World world) { this.world = world; }
    public UUID getUniqueID() { return uuid; } public void setUniqueId(UUID u) { uuid = u; }
    public void setDead() { isDead = true; }
    public double getDistanceSq(Entity o) { double dx = posX - o.posX, dy = posY - o.posY, dz = posZ - o.posZ; return dx * dx + dy * dy + dz * dz; }
    public void onUpdate() { this.onEntityUpdate(); }
    public void onEntityUpdate() {}
    public void applyEntityCollision(Entity entityIn) { this.pushesReceived++; }
}
