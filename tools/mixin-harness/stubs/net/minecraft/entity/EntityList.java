package net.minecraft.entity;
import net.minecraft.util.ResourceLocation;
public class EntityList { public static ResourceLocation getKey(Entity e) { return new ResourceLocation("minecraft:" + e.getClass().getSimpleName().toLowerCase()); } }
