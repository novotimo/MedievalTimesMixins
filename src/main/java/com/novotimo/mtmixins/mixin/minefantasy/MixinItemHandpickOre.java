package com.novotimo.mtmixins.mixin.minefantasy;

import minefantasy.mfr.item.ItemHandpick;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B53 &mdash; a MineFantasy handpick no longer crashes the server on a block that has no item form.
 *
 * <h2>The fault</h2>
 *
 * <p>For each drop of a block it breaks, {@code ItemHandpick.onBlockDestroyed} asks its private
 * {@code isOre(Block, IBlockState)} whether the block is an ore, and {@code isOre} looks the block up in the ore
 * dictionary as {@code new ItemStack(block, 1)}. A block with no item (lit redstone ore, double slabs, a lit
 * furnace) makes an empty stack, and {@code OreDictionary.getOreIDs} throws {@code IllegalArgumentException:
 * Stack can not be invalid!} on an empty stack, which took prod down on 24 October 01:35 (triage S-39). Redstone
 * ore is the common case: hitting it turns it into {@code lit_redstone_ore} before it breaks.
 *
 * <p>The other three MFR callers of {@code getOreIDs} (tooltips, research artefacts, fuels) all check for an
 * empty stack first; only the handpick does not.
 *
 * <h2>The fix</h2>
 *
 * <p>The one {@code isOre} call in {@code onBlockDestroyed} (checked with {@code javap}) is redirected to the same
 * test, with one change: a block without an item is looked up by its pick-block item ({@code Block.getItem}, what
 * middle-click gives), so lit redstone ore counts as redstone ore and keeps the handpick's double drop, and a
 * double slab is looked up as its slab. A block with neither is not an ore. The block is still in the world when
 * this runs (vanilla's {@code tryHarvestBlock} calls {@code onBlockDestroyed} before {@code removeBlock}). Server
 * only: the handpick drops only on the server. MFR is not obfuscated, so the method is named in SRG with
 * {@code remap = false}. Lives in {@code mixins.mtmixins.handpickore.json}, its own file, so it can be switched off
 * in {@code config/mixinbooter.cfg} without B48 and B49.
 */
@Mixin(value = ItemHandpick.class, remap = false)
public abstract class MixinItemHandpickOre {

    @Redirect(method = "func_179218_a",
            at = @At(value = "INVOKE",
                    target = "Lminefantasy/mfr/item/ItemHandpick;isOre(Lnet/minecraft/block/Block;Lnet/minecraft/block/state/IBlockState;)Z"),
            remap = false)
    private boolean mtmixins$isOreSafely(ItemHandpick self, Block block, IBlockState state, ItemStack tool, World world,
                                         IBlockState broken, BlockPos pos, EntityLivingBase user) {
        if (!block.isToolEffective("pickaxe", state)) {
            return false;
        }
        ItemStack stack = new ItemStack(block, 1);
        if (stack.isEmpty()) {
            stack = block.getItem(world, pos, state);
            if (stack.isEmpty()) {
                return false;
            }
        }
        for (int id : OreDictionary.getOreIDs(stack)) {
            String name = OreDictionary.getOreName(id);
            if (name != null && name.startsWith("ore")) {
                return true;
            }
        }
        return false;
    }
}
