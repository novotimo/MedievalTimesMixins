package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.SavedDataWrites;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B42 &mdash; the server no longer freezes for about two seconds every autosave, and on every
 * first opening of a Lootr chest, rewriting {@code world/data/capabilities.dat} when nothing in it changed.
 *
 * <h2>The fault</h2>
 *
 * <p>{@code capabilities.dat} is Forge's {@code WorldCapabilityData}: every mod's world capability in one
 * file. On this server it is almost all Ancient Warfare's structure territory data, 926 territories and
 * 559,000 chunk positions, 4.5 MB of NBT in the test copy and more on prod. Forge's
 * {@code WorldCapabilityData.isDirty()} returns {@code true} unconditionally, so {@code MapStorage} gzips
 * the whole thing on the server thread at every autosave (every 45 seconds), and Lootr's
 * {@code ChestData.createInventory} calls {@code getPerWorldStorage().saveAllData()} on each first opening
 * of a loot chest, which does it again. Prod's profile of 2026-10-22: 4.3% of the server thread in
 * autosave and 2.0% in Lootr's saves, about 1.9 s per autosave, nearly all of it {@code Deflater}.
 *
 * <h2>The fix</h2>
 *
 * <p>{@code MapStorage.saveData} goes through {@link SavedDataWrites}, which builds the same NBT and skips
 * the write when the bytes are identical to the file's last contents. The territory data only changes
 * when new structures generate, so most autosaves skip it. A file that did change is written at gzip's
 * fastest level through a temporary file. Mods still mark data dirty, still have {@code writeToNBT}
 * called at every save, and still find the same file format on disk. Nothing in game changes.
 *
 * <p>Fails soft: any error saving hands that save, and every later one until restart, back to vanilla.
 * Server only. Its own config file, {@code mixins.mtmixins.saveddata.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(MapStorage.class)
public abstract class MixinMapStorageSaveData {

    @Shadow
    @Final
    private ISaveHandler saveHandler;

    @Inject(method = "saveData", at = @At("HEAD"), cancellable = true)
    private void mtmixins$skipUnchangedData(WorldSavedData data, CallbackInfo ci) {
        if (SavedDataWrites.save(this.saveHandler, data)) {
            ci.cancel();
        }
    }
}
