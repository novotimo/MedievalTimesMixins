package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.INamedLootTable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraft.world.storage.loot.LootTableManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stamps each loot table with its own name as the manager hands it out, so the over-fill warning can
 * say which table it was. Diagnostic companion to {@link MixinLootTableAttribution}.
 */
@Mixin(LootTableManager.class)
public abstract class MixinLootTableManagerName {

    @Inject(method = "getLootTableFromLocation", at = @At("RETURN"))
    private void mtmixins$nameIt(ResourceLocation location,
                                 CallbackInfoReturnable<LootTable> cir) {
        LootTable table = cir.getReturnValue();
        if (table instanceof INamedLootTable && location != null) {
            INamedLootTable named = (INamedLootTable) table;
            // The empty table is a shared singleton handed out for every missing table, so naming it
            // after whichever one asked last would be actively misleading.
            if (named.mtmixins$lootTableName() == null) {
                named.mtmixins$setLootTableName(location.toString());
            } else if (!named.mtmixins$lootTableName().equals(location.toString())) {
                named.mtmixins$setLootTableName("<shared: " + named.mtmixins$lootTableName()
                        + " and " + location + ">");
            }
        }
    }
}
