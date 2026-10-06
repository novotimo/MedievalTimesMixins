package net.minecraft.block;
import net.minecraft.block.state.IBlockState;
public class Block {
    private final String name;
    private final IBlockState def = new IBlockState() { public Block getBlock() { return Block.this; } public String toString() { return name; } };
    public Block(String name) { this.name = name; }
    public IBlockState getDefaultState() { return def; }
    public String toString() { return name; }
}
