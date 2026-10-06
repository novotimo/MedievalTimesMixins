package net.minecraft.util;
public class ResourceLocation { private final String s; public ResourceLocation(String s) { this.s = s; } public String toString() { return s; } public boolean equals(Object o) { return o instanceof ResourceLocation && ((ResourceLocation) o).s.equals(s); } public int hashCode() { return s.hashCode(); } }
