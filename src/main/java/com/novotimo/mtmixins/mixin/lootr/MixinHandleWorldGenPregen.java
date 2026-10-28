package com.novotimo.mtmixins.mixin.lootr;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B54 &mdash; Lootr's chest conversion works in chunks Chunk Pregenerator is holding, instead of leaving
 * an emptied chest that cannot be opened.
 *
 * <h2>The fault</h2>
 *
 * <p>Lootr turns a generated loot chest into its own per-player chest in {@code HandleWorldGen.replaceOldLootBlockAt}:
 * it empties the old tile entity, sets the Lootr block, removes the tile entity at the position and takes the one the
 * chunk then holds. Chunk Pregenerator puts the chunks it generates straight into the provider's map
 * ({@code ChunkHelper.storeChunk}) and never calls {@code Chunk.onLoad}, so they are not marked loaded, and
 * {@code Chunk.removeTileEntity} does nothing on a chunk that is not loaded. The old tile entity therefore stays, and
 * {@code Chunk.setBlockState} finds it and creates no new one. The block becomes a Lootr chest holding the old,
 * emptied chest: Lootr logs "replacement TE ... is not an ILootTile" (or "... is not an LootrInventoryTileEntity"),
 * and the chest never opens, because Lootr only opens its own tile entities. Prod's first pregen on 2026-10-28 did
 * this to every chest in Ancient Warfare ships and vanilla structures it generated; in 187 log files of ordinary
 * exploration before it, the error never appeared.
 *
 * <h2>The fix</h2>
 *
 * <p>At the head of {@code replaceOldLootBlockAt}, when the chunk is not loaded, the old tile entity is taken off the
 * world's lists ({@code World.removeTileEntity}, which still does that part), out of the chunk's map and invalidated,
 * which is what {@code Chunk.removeTileEntity} would have done in a loaded chunk. Lootr then creates and fills its
 * own tile entity as usual. Loaded chunks are unchanged. Server only (the conversion runs only on the server). Lootr
 * is not obfuscated, so the method is named as it is in the jar with {@code remap = false}; {@code @Pseudo}, so it
 * does nothing without Lootr. Lives in {@code mixins.mtmixins.lootrpregen.json}, its own file, so it can be switched
 * off in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Pseudo
@Mixin(targets = "noobanidus.mods.lootr.event.HandleWorldGen", remap = false)
public abstract class MixinHandleWorldGenPregen {

    @Inject(method = "replaceOldLootBlockAt", at = @At("HEAD"), remap = false)
    private static void mtmixins$dropOldTileInUnloadedChunk(Chunk chunk, BlockPos pos, IBlockState state,
                                                           CallbackInfoReturnable<TileEntity> cir) {
        if (chunk == null || chunk.isLoaded()) {
            return;
        }
        chunk.getWorld().removeTileEntity(pos);
        TileEntity old = chunk.getTileEntityMap().remove(pos);
        if (old != null) {
            old.invalidate();
        }
    }
}
