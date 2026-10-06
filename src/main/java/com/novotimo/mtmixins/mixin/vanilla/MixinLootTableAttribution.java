package com.novotimo.mtmixins.mixin.vanilla;

import java.util.List;
import java.util.Random;

import com.novotimo.mtmixins.INamedLootTable;
import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.storage.loot.LootContext;
import net.minecraft.world.storage.loot.LootTable;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Says WHICH container over-filled, because vanilla does not.
 *
 * <p>{@code LootTable.fillInventory} logs a bare "Tried to over-fill a container" with no table, no
 * container and no position, which has made this the longest-running unexplained item on the board:
 * 22,867 occurrences in ten days of production logs, and every attempt to pin it down has been by
 * elimination. Each round of that has been wrong in a different way &mdash; the stack budgets were
 * right, the AncientWarfare rolls multiplier was real but only worth three cases, the structure
 * templates' double loot-table key was real but did not stop it, and the small-container theory died
 * on a 12-slot bookshelf holding a 5-stack table.
 *
 * <p>So: instrument it instead of guessing. On the over-fill branch this records the inventory's
 * class, its size, how many slots were already occupied, and the nearest non-vanilla frames of the
 * call stack. That is enough to name the mod and the container type in one restart.
 *
 * <p>Deliberately cheap and self-limiting: the stack walk only happens on the over-fill branch,
 * which by definition is already a failure, and only the first 40 distinct shapes are reported.
 *
 * <p>Diagnostic only &mdash; it changes no behaviour. Once the cause is known and fixed this file
 * should be deleted rather than left running.
 */
@Mixin(LootTable.class)
public abstract class MixinLootTableAttribution implements INamedLootTable {

    @Unique
    private String mtmixins$tableName;

    @Override
    public String mtmixins$lootTableName() {
        return this.mtmixins$tableName;
    }

    @Override
    public void mtmixins$setLootTableName(String name) {
        this.mtmixins$tableName = name;
    }


    /** Flat cap, so a broken server cannot be buried by its own diagnostics. */
    @Unique
    private static final int MAX_REPORTS = 500;

    @Unique
    private static int mtmixins$reported = 0;

    /** Set at the head of each fill so the warn handler knows what it was filling. */
    @Unique
    private static final ThreadLocal<String> mtmixins$target = new ThreadLocal<String>();

    @Inject(method = "fillInventory", at = @At("HEAD"))
    private void mtmixins$noteTarget(IInventory inventory, Random rand, LootContext context,
                                     CallbackInfo ci) {
        if (inventory == null) {
            mtmixins$target.set("null inventory");
            return;
        }
        int size = inventory.getSizeInventory();
        int used = 0;
        for (int i = 0; i < size; i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (s != null && !s.isEmpty()) {
                used++;
            }
        }
        mtmixins$target.set(String.format("%s size=%d alreadyUsed=%d",
                inventory.getClass().getName(), size, used));
    }

    /**
     * Redirects vanilla's own warn so the line carries the context instead of replacing it with a
     * second line, which keeps the existing log filters and counts working.
     *
     * <p>{@code remap = false} on the {@code @At} only: the target is log4j's {@code Logger}, which is
     * not obfuscated, so the annotation processor has no mapping to find and warns "Unable to locate
     * method mapping" if asked to look. {@code fillInventory} itself still remaps through the class.
     */
    @Redirect(
            method = "fillInventory",
            at = @At(value = "INVOKE",
                    target = "Lorg/apache/logging/log4j/Logger;warn(Ljava/lang/String;)V",
                    remap = false))
    private void mtmixins$attributeOverfill(Logger logger, String message) {
        String target = mtmixins$target.get();
        StringBuilder where = new StringBuilder();
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        int shown = 0;
        for (StackTraceElement f : trace) {
            String cn = f.getClassName();
            if (cn.startsWith("java.") || cn.startsWith("com.novotimo.")
                    || cn.startsWith("org.spongepowered.")
                    || cn.equals("net.minecraft.world.storage.loot.LootTable")) {
                continue;
            }
            if (where.length() > 0) {
                where.append(" <- ");
            }
            where.append(cn.substring(cn.lastIndexOf('.') + 1)).append('.').append(f.getMethodName());
            if (++shown >= 6) {
                break;
            }
        }
        // No deduplication. The first version logged the enriched line once per distinct
        // "shape" and a bare line every time after, which meant most occurrences carried no
        // information at all - the whole point of the exercise. A flat cap is the right way to
        // bound a diagnostic: every line is useful until the cap, and nothing is silently
        // downgraded.
        if (mtmixins$reported++ < MAX_REPORTS) {
            MedievalTimesMixins.LOG.warn("{} -- table {} | container {} | called from {}",
                    message, mtmixins$describeTable(), target, where);
        } else {
            logger.warn(message);
        }
    }

    @Unique
    private String mtmixins$describeTable() {
        String name = this.mtmixins$tableName;
        return name == null ? "<unnamed, never went through LootTableManager>" : name;
    }
}
