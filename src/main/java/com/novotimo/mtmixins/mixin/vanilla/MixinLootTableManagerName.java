package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.INamedLootTable;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.storage.loot.LootTable;
import net.minecraft.world.storage.loot.LootTableManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stamps each loot table with its own name as the manager hands it out, so the over-fill warning can
 * say which table it was. Diagnostic companion to {@link MixinLootTableAttribution}.
 */
@Mixin(LootTableManager.class)
public abstract class MixinLootTableManagerName {

    @Unique
    private static final String MTMIXINS$SHARED_PREFIX = "<shared: ";

    @Inject(method = "getLootTableFromLocation", at = @At("RETURN"))
    private void mtmixins$nameIt(ResourceLocation location,
                                 CallbackInfoReturnable<LootTable> cir) {
        LootTable table = cir.getReturnValue();
        if (table instanceof INamedLootTable && location != null) {
            INamedLootTable named = (INamedLootTable) table;
            String current = named.mtmixins$lootTableName();
            String asked = location.toString();
            // The empty table is a shared singleton handed out for every missing table, so naming it
            // after whichever one asked last would be actively misleading. It is marked shared once
            // and then left alone: this runs on every lookup (every mob death, every loot chest), and
            // the first version appended to the name on each lookup that did not match it, which
            // after the first two distinct names was every lookup, so the string grew without bound.
            if (current == null) {
                named.mtmixins$setLootTableName(asked);
            } else if (!current.equals(asked) && !current.startsWith(MTMIXINS$SHARED_PREFIX)) {
                named.mtmixins$setLootTableName(MTMIXINS$SHARED_PREFIX + current + ", " + asked
                        + " and possibly others>");
            }
        }
    }
}
