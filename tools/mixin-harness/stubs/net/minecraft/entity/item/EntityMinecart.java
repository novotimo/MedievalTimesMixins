package net.minecraft.entity.item;
import net.minecraft.world.World;
/** As vanilla: onUpdate is overridden and never calls super.onUpdate() / onEntityUpdate(). */
public class EntityMinecart extends net.minecraft.entity.Entity {
    public EntityMinecart(World w) { super(w); }
    public void onUpdate() { /* rolling amplitude, portal handling, rail movement ... no super call */ }
}
