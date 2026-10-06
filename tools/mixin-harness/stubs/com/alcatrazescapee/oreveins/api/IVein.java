package com.alcatrazescapee.oreveins.api;
import net.minecraft.util.math.BlockPos;
public interface IVein<T> { BlockPos getPos(); IVeinType<?> getType(); boolean inRange(int x, int z); }
