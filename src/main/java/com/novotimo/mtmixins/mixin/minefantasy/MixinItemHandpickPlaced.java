package com.novotimo.mtmixins.mixin.minefantasy;

import java.util.ArrayList;
import java.util.List;
import minefantasy.mfr.api.mining.RandomOre;
import minefantasy.mfr.item.ItemHandpick;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B49 &mdash; a MineFantasy handpick rolls its random ore only on blocks the break does not give
 * back, so placing and breaking the same block is no longer an ore farm.
 *
 * <h2>The fault</h2>
 *
 * <p>{@code ItemHandpick.onBlockDestroyed} rolls MFR's random-ore table ({@code RandomOre}) on every block it
 * breaks that the table names, checking only the block and its height. The table names {@code ore:stone}
 * (which Chisel's stone-brick family joins), {@code ore:stoneLimestone}, dirt, grass, netherrack and soul
 * sand. Natural stone breaks into cobblestone, so it pays once. Chiselled stone, granite, diorite, andesite,
 * dirt, netherrack, soul sand and limestone cobble drop themselves, so one block placed and broken over and over
 * paid out forever: flux, copper and tin from stone, blaze powder, quartz and glowstone from netherrack
 * (Tim, 28 October, S-36).
 *
 * <h2>The fix</h2>
 *
 * <p>The one {@code RandomOre.getDroppedItems} call in {@code onBlockDestroyed} (checked with {@code javap})
 * returns nothing when the break gives the block back: when any of the block's own drops (fortune 0) is the
 * block's item, any metadata, or when the handpick has Silk Touch and the block can be silk harvested. This
 * runs before {@code removeBlock} (vanilla's {@code tryHarvestBlock} calls {@code onBlockDestroyed} first),
 * so the block is still there to ask. Natural stone (cobblestone), smooth limestone (limestone cobble) and
 * grass (dirt) still roll; so does any
 * block the table names that drops something else. This is a balance change as well as a fix: naturally
 * mined netherrack, dirt, granite and soul sand stop paying out too, because nothing can tell a natural
 * block from a placed one.
 *
 * <p>The handpick's double drop for real ores is a separate path and unchanged; so is B48's copy of the
 * drops, which still applies when the roll happens. Server only: the handpick drops only on the server. MFR
 * is not obfuscated, so the method is named in SRG with {@code remap = false}. Lives in
 * {@code mixins.mtmixins.handpickplaced.json}, its own file, so this rule can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild and without losing B48.
 */
@Mixin(value = ItemHandpick.class, remap = false)
public abstract class MixinItemHandpickPlaced {

    @Redirect(method = "func_179218_a",
            at = @At(value = "INVOKE",
                    target = "Lminefantasy/mfr/api/mining/RandomOre;getDroppedItems(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/block/Block;IIZI)Ljava/util/ArrayList;"),
            remap = false)
    private ArrayList<ItemStack> mtmixins$rollOnlyIfConsumed(EntityLivingBase user, Block block, int harvest, int fortune,
                                                             boolean silk, int y, ItemStack tool, World world,
                                                             IBlockState state, BlockPos pos, EntityLivingBase breaker) {
        if (mtmixins$givesBlockBack(world, pos, state, block, silk, user)) {
            return new ArrayList<>();
        }
        return RandomOre.getDroppedItems(user, block, harvest, fortune, silk, y);
    }

    private static boolean mtmixins$givesBlockBack(World world, BlockPos pos, IBlockState state, Block block,
                                                   boolean silk, EntityLivingBase user) {
        if (silk && block.canSilkHarvest(world, pos, state, user instanceof EntityPlayer ? (EntityPlayer) user : null)) {
            return true;
        }
        Item own = Item.getItemFromBlock(block);
        if (own == null) {
            return false;
        }
        List<ItemStack> drops = block.getDrops(world, pos, state, 0);
        for (ItemStack drop : drops) {
            if (!drop.isEmpty() && drop.getItem() == own) {
                return true;
            }
        }
        return false;
    }
}
