package net.minecraft.util.math;
public class BlockPos {
    private final int x, y, z;
    public BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }
    public BlockPos down() { return new BlockPos(x, y - 1, z); }
    public boolean equals(Object o) { if (!(o instanceof BlockPos)) return false; BlockPos p = (BlockPos) o; return p.x == x && p.y == y && p.z == z; }
    public int hashCode() { return (x * 31 + y) * 31 + z; }
    public String toString() { return "(" + x + "," + y + "," + z + ")"; }
}
