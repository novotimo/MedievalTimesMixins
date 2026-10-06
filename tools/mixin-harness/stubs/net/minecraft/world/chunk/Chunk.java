package net.minecraft.world.chunk;
import java.util.*;
import net.minecraft.entity.Entity;
public class Chunk { public final List<Entity> entities = new ArrayList<Entity>(); public boolean dirty; public void removeEntity(Entity e) { entities.remove(e); dirty = true; } public void markDirty() { dirty = true; } }
