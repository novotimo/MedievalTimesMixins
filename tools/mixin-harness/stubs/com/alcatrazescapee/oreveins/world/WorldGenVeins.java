package com.alcatrazescapee.oreveins.world;
import java.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import com.alcatrazescapee.oreveins.api.IVein;
/** Same instance for every world, same private generate() signature and local layout as upstream 2.0.15. */
public class WorldGenVeins {
    public void generate(Random random, int chunkX, int chunkZ, World world, List<IVein> veins) {
        int xoff = chunkX * 16 + 8;
        int zoff = chunkZ * 16 + 8;
        for (IVein vein : veins) generate(world, random, xoff, zoff, vein);
    }
    private void generate(World world, Random random, int xOff, int zOff, IVein<?> vein) {
        for (int x = xOff; x < 16 + xOff; x++) {
            for (int z = zOff; z < 16 + zOff; z++) {
                Biome biomeAt = world.getBiome(new BlockPos(x, 0, z));
                if (vein.getType().matchesBiome(biomeAt) && vein.inRange(x, z)) {
                    random.nextFloat();
                }
            }
        }
    }
}
