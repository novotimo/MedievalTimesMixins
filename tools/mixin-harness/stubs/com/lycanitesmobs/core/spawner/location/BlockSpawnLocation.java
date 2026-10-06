package com.lycanitesmobs.core.spawner.location;
import java.util.*;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
public class BlockSpawnLocation extends SpawnLocation {
    public BlockPos rangeMax = new BlockPos(16, 1, 16);
    public int yMin = -1, yMax = -1, requiredBlockTypes = 0;
    public boolean surface = true, underground = true;
    public Set<Block> blocks = new HashSet<Block>();
    public String listType = "whitelist";
    @Override
    public List<BlockPos> getSpawnPositions(World world, EntityPlayer player, BlockPos triggerPos) {
        List<BlockPos> spawnPositions = new ArrayList<BlockPos>();
        Map<Block, Integer> validBlocksFound = new HashMap<Block, Integer>();
        for (int y = triggerPos.getY() - this.rangeMax.getY(); y <= triggerPos.getY() + this.rangeMax.getY(); y++) {
            int yMin = 0; if (this.yMin >= 0) yMin = this.yMin; if (y < yMin) y = yMin;
            int yMax = world.getActualHeight(); if (this.yMax >= 0) yMax = Math.min(this.yMax, yMax); if (y >= yMax) break;
            for (int x = triggerPos.getX() - this.rangeMax.getX(); x <= triggerPos.getX() + this.rangeMax.getX(); x++) {
                for (int z = triggerPos.getZ() - this.rangeMax.getZ(); z <= triggerPos.getZ() + this.rangeMax.getZ(); z++) {
                    BlockPos spawnPos = new BlockPos(x, y, z);
                    IBlockState blockState = world.getBlockState(spawnPos);
                    if (blockState.getBlock() instanceof BlockLiquid) continue;
                    if (this.isValidBlock(world, spawnPos)) {
                        spawnPositions.add(spawnPos);
                        if (this.requiredBlockTypes > 0) {
                            Block block = world.getBlockState(spawnPos).getBlock();
                            validBlocksFound.put(block, validBlocksFound.containsKey(block) ? validBlocksFound.get(block) + 1 : 1);
                        }
                    }
                }
            }
        }
        return spawnPositions;
    }
    public boolean isValidBlock(World world, BlockPos blockPos) {
        if (!this.surface || !this.underground) {
            if (world.canSeeSky(blockPos)) { if (!this.surface) return false; }
            else { if (!this.underground) return false; }
        }
        if (this.blocks.isEmpty()) return true;
        Block block = world.getBlockState(blockPos).getBlock();
        return "blacklist".equals(this.listType) ? !this.blocks.contains(block) : this.blocks.contains(block);
    }
}
