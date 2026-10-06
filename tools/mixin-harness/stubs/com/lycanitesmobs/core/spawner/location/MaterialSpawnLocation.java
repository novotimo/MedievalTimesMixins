package com.lycanitesmobs.core.spawner.location;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
public class MaterialSpawnLocation extends BlockSpawnLocation {
    @Override
    public boolean isValidBlock(World world, BlockPos blockPos) {
        IBlockState blockState = world.getBlockState(blockPos);
        if (blockState == null) return false;
        if (!this.surface || !this.underground) {
            if (world.canSeeSky(blockPos)) { if (!this.surface) return false; }
            else { if (!this.underground) return false; }
        }
        return true;
    }
}
