package com.lycanitesmobs.core.spawner.location;
import java.util.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
public class RandomSpawnLocation extends BlockSpawnLocation {
    public boolean solidGround = true;
    @Override
    public List<BlockPos> getSpawnPositions(World world, EntityPlayer player, BlockPos triggerPos) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        for (int dx = -24; dx <= 24; dx += 8) for (int dz = -24; dz <= 24; dz += 8) {
            int y = this.getRandomYCoord(world, new BlockPos(triggerPos.getX() + dx, triggerPos.getY(), triggerPos.getZ() + dz));
            if (y > -1) out.add(new BlockPos(triggerPos.getX() + dx, y, triggerPos.getZ() + dz));
        }
        return out;
    }
    public int getRandomYCoord(World world, BlockPos triggerPos) {
        for (int nextY = triggerPos.getY() - 2; nextY <= triggerPos.getY() + 2; nextY++) {
            BlockPos spawnPos = new BlockPos(triggerPos.getX(), nextY, triggerPos.getZ());
            if (this.isValidBlock(world, spawnPos)) return nextY;
        }
        return -1;
    }
    @Override
    public boolean isValidBlock(World world, BlockPos blockPos) {
        if (!super.isValidBlock(world, blockPos)) return false;
        if (this.solidGround) return world.getBlockState(blockPos.down()) != null;
        return true;
    }
}
