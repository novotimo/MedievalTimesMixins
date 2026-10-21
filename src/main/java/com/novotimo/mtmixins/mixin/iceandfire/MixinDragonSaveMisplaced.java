package com.novotimo.mtmixins.mixin.iceandfire;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.novotimo.mtmixins.util.MisplacedEntities;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.passive.EntityTameable;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.DimensionManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B38 &mdash; a stale dragon record stuck in the wrong chunk goes away instead of coming back on
 * every load. Dragon corpses and every other dragon save exactly as before.
 *
 * <h2>The fault</h2>
 *
 * <pre>
 * [Server thread/WARN] [minecraft/Chunk]: Wrong location! (24, 221) should be (-34, 154),
 *     EntityFireDragon['Zephyra'/10687, l='Spawn', x=393.20, y=69.80, z=3538.12]
 * </pre>
 *
 * <p>When a chunk loads an entity whose position lies in another chunk, vanilla's {@code Chunk.addEntity}
 * warns, calls {@code setDead()} and still adds it to the chunk's entity list. The world refuses the dead
 * entity, so it never appears in game and no command can reach it. For any other entity the next save
 * drops it, because {@code Entity.writeToNBTOptional} skips dead entities. Ice and Fire 1.9.1 overrides
 * that method in {@code EntityDragonBase} to write the dragon unconditionally (upstream 954847a, "fix
 * #168": a baby dragon riding a player vanished on relog, and vanilla also skips riding entities). So the
 * dead record is written back into the same wrong chunk on every save and dies again on every load.
 *
 * <p>Prod's log, 4&ndash;21 October 2026: 1,263 warnings for a fire dragon named Zephyra (two records, one
 * in a Spawn-world chunk and one in an overworld chunk) and 204 for an unnamed fire dragon. In the test
 * server's copy of the world both Zephyra records share the UUID and owner of the live, correctly placed
 * Zephyra, with health 60 against its 813: stale snapshots.
 *
 * <h2>The fix</h2>
 *
 * <p>Narrow on purpose. Ice and Fire's override stays in charge of every dragon, so a corpse being
 * harvested (health 0, {@code ModelDead}, not {@code isDead} until its skull is taken) and a dragon riding a
 * player save exactly as they do today. The only dragons this stops saving are ones vanilla's
 * wrong-location check killed while their chunk was loading, which {@code vanilla.MixinChunkMisplacedEntity}
 * records in {@link MisplacedEntities}. Such a record is already gone from play: it was never added to the
 * world. It disappears the next time its chunk saves.
 *
 * <p>Because a misplaced record could in principle be the only copy of somebody's dragon (one caught
 * crossing a chunk border during a save), a tamed one's full data is written to
 * {@code <world>/mtmixins/dropped-dragons/<uuid>-<time>.dat} first, and {@code mtmixins/dragonsave} logs its
 * name, owner, UUID and position. Wild ones are logged only.
 *
 * <p>{@code remap = false} with both the MCP and the SRG name, as the other Ice and Fire registers do; the
 * shipped jar has {@code func_70039_c}. Server only. Lives in {@code mixins.mtmixins.dragonsave.json} with
 * its first half, so both can be switched off in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.EntityDragonBase", remap = false)
public abstract class MixinDragonSaveMisplaced {

    @Unique
    private static final Logger MTMIXINS$LOG = LogManager.getLogger("mtmixins/dragonsave");

    /** A runaway source of misplaced dragons must not fill the disk with backups. */
    @Unique
    private static final int MTMIXINS$MAX_BACKUPS_PER_RUN = 200;

    @Unique
    private static final AtomicInteger MTMIXINS$BACKUPS = new AtomicInteger();

    @Inject(method = {"writeToNBTOptional", "func_70039_c"}, at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$skipMisplaced(NBTTagCompound compound, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (!self.isDead || !MisplacedEntities.isMarked(self)) {
            return; // every dragon vanilla did not kill for being in the wrong chunk: Ice and Fire's own save
        }
        cir.setReturnValue(false);
        if (!MisplacedEntities.firstReport(self)) {
            return;
        }

        try {
            UUID owner = self instanceof EntityTameable ? ((EntityTameable) self).getOwnerId() : null;
            String where = "dim " + self.dimension + ", position " + Math.round(self.posX) + " " + Math.round(self.posY) + " "
                    + Math.round(self.posZ) + ", saved in chunk (" + self.chunkCoordX + ", " + self.chunkCoordZ + ")";
            String who = "'" + self.getName() + "' (uuid " + self.getUniqueID() + ", owner " + owner + ")";
            if (owner == null) {
                MTMIXINS$LOG.info("Dropping stale wild dragon record {}, {}", who, where);
                return;
            }
            String backup = mtmixins$backup(self);
            MTMIXINS$LOG.info("Dropping stale tamed dragon record {}, {}; its data is backed up in {}", who, where, backup);
        } catch (Throwable t) {
            MTMIXINS$LOG.warn("Dropping a stale dragon record; could not describe or back it up", t);
        }
    }

    @Unique
    private static String mtmixins$backup(Entity dragon) {
        if (MTMIXINS$BACKUPS.incrementAndGet() > MTMIXINS$MAX_BACKUPS_PER_RUN) {
            return "nothing (backup limit of " + MTMIXINS$MAX_BACKUPS_PER_RUN + " this run reached)";
        }
        try {
            File root = DimensionManager.getCurrentSaveRootDirectory();
            if (root == null) {
                return "nothing (no world folder)";
            }
            File dir = new File(new File(root, "mtmixins"), "dropped-dragons");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return "nothing (could not create " + dir + ")";
            }
            NBTTagCompound tag = new NBTTagCompound();
            // writeToNBT, not writeToNBTOptional: that is the call being cancelled here.
            dragon.writeToNBT(tag);
            ResourceLocation key = EntityList.getKey(dragon);
            if (key != null) {
                tag.setString("id", key.toString());
            }
            File file = new File(dir, dragon.getUniqueID() + "-" + System.currentTimeMillis() + ".dat");
            try (OutputStream out = new FileOutputStream(file)) {
                CompressedStreamTools.writeCompressed(tag, out);
            }
            return file.getPath();
        } catch (Throwable t) {
            return "nothing (" + t + ")";
        }
    }
}
