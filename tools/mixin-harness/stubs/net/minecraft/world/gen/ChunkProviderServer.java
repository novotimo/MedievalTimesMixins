package net.minecraft.world.gen;
import java.util.*;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
public class ChunkProviderServer { public final Map<Long, Chunk> chunks = new HashMap<Long, Chunk>(); public Chunk getLoadedChunk(int x, int z) { return chunks.get(World.key(x, z)); } }
