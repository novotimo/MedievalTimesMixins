package net.minecraft.world;
import java.util.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
/** Server-side World semantics that matter here: getBlockState/canSeeSky go through provideChunk and so LOAD or GENERATE the chunk; isBlockLoaded does not. */
public abstract class World {
    protected final List<Entity> unloadedEntityList = new ArrayList<Entity>();
    public final List<Entity> loadedEntityList = new ArrayList<Entity>();
    public WorldProvider provider = new WorldProvider();
    public long totalTime;
    public final Set<Long> loadedChunks = new HashSet<Long>();
    public final List<String> chunksGenerated = new ArrayList<String>();
    public Biome biomeForAll;
    public static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }
    public long getTotalWorldTime() { return totalTime; }
    public boolean isBlockLoaded(BlockPos pos) { return loadedChunks.contains(key(pos.getX() >> 4, pos.getZ() >> 4)); }
    private void provideChunk(BlockPos pos) {
        if (!isBlockLoaded(pos)) { loadedChunks.add(key(pos.getX() >> 4, pos.getZ() >> 4)); chunksGenerated.add((pos.getX() >> 4) + "," + (pos.getZ() >> 4)); }
    }
    public IBlockState getBlockState(BlockPos pos) { provideChunk(pos); return Blocks.STONE.getDefaultState(); }
    public boolean canSeeSky(BlockPos pos) { provideChunk(pos); return pos.getY() >= 64; }
    public int getActualHeight() { return 256; }
    public Biome getBiome(BlockPos pos) { return biomeForAll; }
    public List<Entity> getEntitiesInAABBexcluding(Entity self) { List<Entity> l = new ArrayList<Entity>(loadedEntityList); l.remove(self); return l; }
}
