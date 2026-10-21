package com.novotimo.mtmixins.mixin.vanilla;

import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.RegistryNamespaced;
import net.minecraft.world.World;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B36 &mdash; signs saved by Redstone++'s sign module keep their text now that the module is
 * off.
 *
 * <h2>The fault</h2>
 *
 * <pre>
 * [Server thread/WARN] [minecraft/TileEntity]: Skipping BlockEntity with id minecraft:sign_fix
 * </pre>
 *
 * <p>Redstone++ 1.3e's {@code ImprovedSigns} module ({@code enableSignFix}) swaps vanilla's sign blocks
 * for its own, whose tile entity {@code TileEntitySignFix} is registered as {@code sign_fix} &mdash; so
 * {@code minecraft:sign_fix} on disk. Every sign placed while the module was on was saved under that id.
 * Prod now runs with the module off, so the id is unregistered, {@code TileEntity.create} finds no class
 * for it, prints the warning above and returns null. The sign block loads without a tile entity, shows no
 * text, and the next save writes the chunk without it: the text is gone for good. The production log
 * showed 285 of these between 4 and 14 October 2026, every day; the test server's older copy of the
 * world still holds 4,524 such signs, 3,370 of them with text.
 *
 * <h2>The fix</h2>
 *
 * <p>{@code TileEntitySignFix} extends vanilla {@code TileEntitySign} and overrides only
 * {@code isEditable}, so its NBT is a vanilla sign's ({@code Text1}&ndash;{@code Text4}, position,
 * {@code ForgeData}); the test-world scan found no other keys. The block underneath is vanilla's
 * {@code standing_sign} or {@code wall_sign} again once the module is off. So the stored id is simply
 * rewritten to {@code minecraft:sign} before vanilla looks it up: the sign loads as a vanilla sign with
 * its text, and is saved back under the vanilla id, so each one is converted once.
 *
 * <p>It applies only while nothing has registered {@code minecraft:sign_fix}. If the module is ever
 * switched back on, the id resolves to Redstone++'s class again and this leaves it alone.
 *
 * <p>Cost: one string comparison per tile entity loaded; the registry lookup runs only for a
 * {@code sign_fix} record.
 *
 * <p>Each conversion is logged by {@code mtmixins/signfix}, individually for the first
 * {@value #MTMIXINS$LOG_EACH} of a run and then as a running total every
 * {@value #MTMIXINS$LOG_EVERY}, so flying over an old town cannot flood the log.
 *
 * <p>Lives in {@code mixins.mtmixins.signfix.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild. Not marked server only: a singleplayer world with
 * old signs has the same problem, and on a client that never loads chunk NBT it does nothing.
 */
@Mixin(TileEntity.class)
public abstract class MixinTileEntitySignFixId {

    @Unique
    private static final Logger MTMIXINS$LOG = LogManager.getLogger("mtmixins/signfix");

    @Unique
    private static final String MTMIXINS$OLD_ID = "minecraft:sign_fix";

    @Unique
    private static final String MTMIXINS$NEW_ID = "minecraft:sign";

    @Unique
    private static final int MTMIXINS$LOG_EACH = 50;

    @Unique
    private static final int MTMIXINS$LOG_EVERY = 100;

    @Unique
    private static final AtomicInteger MTMIXINS$CONVERTED = new AtomicInteger();

    @Shadow
    @Final
    private static RegistryNamespaced<ResourceLocation, Class<? extends TileEntity>> REGISTRY;

    @Inject(method = "create", at = @At("HEAD"))
    private static void mtmixins$renameSignFix(World worldIn, NBTTagCompound compound,
                                               CallbackInfoReturnable<TileEntity> cir) {
        if (compound == null || !MTMIXINS$OLD_ID.equals(compound.getString("id"))) {
            return;
        }
        if (REGISTRY.getObject(new ResourceLocation(MTMIXINS$OLD_ID)) != null) {
            return; // Redstone++'s sign module is on again; its own class handles the record.
        }
        compound.setString("id", MTMIXINS$NEW_ID);

        int n = MTMIXINS$CONVERTED.incrementAndGet();
        if (n <= MTMIXINS$LOG_EACH) {
            String dim = worldIn != null && worldIn.provider != null
                    ? String.valueOf(worldIn.provider.getDimension()) : "?";
            MTMIXINS$LOG.info("Loaded Redstone++ sign as a vanilla sign, text kept: dim {} at {} {} {}{}",
                    dim, compound.getInteger("x"), compound.getInteger("y"), compound.getInteger("z"),
                    n == MTMIXINS$LOG_EACH ? " (further conversions are counted every "
                            + MTMIXINS$LOG_EVERY + ")" : "");
        } else if (n % MTMIXINS$LOG_EVERY == 0) {
            MTMIXINS$LOG.info("Loaded {} Redstone++ signs as vanilla signs since start, text kept", n);
        }
    }
}
