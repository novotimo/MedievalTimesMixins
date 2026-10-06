package tests;
import java.util.*;
import com.alcatrazescapee.oreveins.api.IVein;
import com.alcatrazescapee.oreveins.api.IVeinType;
import com.alcatrazescapee.oreveins.world.WorldGenVeins;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
public class OreVeinsTest {
    static final class CountingWorld extends World { int calls; CountingWorld(String b) { biomeForAll = new Biome(b); } public Biome getBiome(BlockPos p) { calls++; return biomeForAll; } }
    public static void run() {
        final List<String> seen = new ArrayList<String>();
        final IVeinType<?> type = new IVeinType<Object>() { public int getChunkRadius() { return 1; } public boolean matchesBiome(Biome b) { seen.add(b.name); return true; } };
        IVein<?> vein = new IVein<Object>() { public BlockPos getPos() { return new BlockPos(16, 30, 16); } public IVeinType<?> getType() { return type; } public boolean inRange(int x, int z) { return true; } };
        List<IVein> veins = new ArrayList<IVein>(); veins.add(vein);
        WorldGenVeins gen = new WorldGenVeins();
        CountingWorld overworld = new CountingWorld("forest"), nether = new CountingWorld("hell");
        gen.generate(new Random(1), 0, 0, overworld, veins);
        int firstCalls = overworld.calls;
        seen.clear();
        gen.generate(new Random(1), 0, 0, nether, veins);
        int hell = Collections.frequency(seen, "hell"), forest = Collections.frequency(seen, "forest");
        System.out.println("[B2b] chunk 0,0 in a second world after the first: matchesBiome saw " + hell + " x hell, " + forest + " x forest (expected 256 x hell)");
        System.out.println("[B2b] cross-world: " + (hell == 256 && forest == 0 ? "PASS" : "FAIL"));
        seen.clear(); overworld.calls = 0;
        gen.generate(new Random(1), 0, 0, overworld, veins);
        int passA = overworld.calls; overworld.calls = 0;
        gen.generate(new Random(1), 0, 0, overworld, veins);
        int passB = overworld.calls;
        System.out.println("[B2b] back in world 1: first pass " + passA + " getBiome calls, repeat pass " + passB + " calls (cache hits), matchesBiome saw " + Collections.frequency(seen, "forest") + "/512 x forest");
        System.out.println("[B2b] same-world cache: " + (firstCalls == 256 && passB == 0 && Collections.frequency(seen, "forest") == 512 ? "PASS" : "FAIL"));
    }
}
