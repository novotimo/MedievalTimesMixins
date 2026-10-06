package net.minecraft.item;
public class ItemStack {
    public static final ItemStack EMPTY = new ItemStack(null, 0, 64);
    public final Item item; private int count; private final int max;
    public ItemStack(Item item, int count, int max) { this.item = item; this.count = count; this.max = max; }
    public boolean isEmpty() { return this == EMPTY || item == null || count <= 0; }
    public int getCount() { return count; } public void setCount(int c) { count = c; } public int getMaxStackSize() { return max; }
    public void grow(int n) { count += n; } public void shrink(int n) { count -= n; }
    public ItemStack copy() { return new ItemStack(item, count, max); }
    public ItemStack splitStack(int n) { int t = Math.min(n, count); ItemStack s = copy(); s.setCount(t); count -= t; return s; }
    public static boolean areItemsEqual(ItemStack a, ItemStack b) { return a.item == b.item; }
    public static boolean areItemStackTagsEqual(ItemStack a, ItemStack b) { return true; }
    public boolean areCapsCompatible(ItemStack o) { return true; }
}
