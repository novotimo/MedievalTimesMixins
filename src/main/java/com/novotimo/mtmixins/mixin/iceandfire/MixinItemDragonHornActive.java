package com.novotimo.mtmixins.mixin.iceandfire;

import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B16 &mdash; the ghost dragon. A dragon horn releases a dragon carrying the <i>stored</i>
 * UUID, so if the original was not actually removed you end up with two dragons answering to one
 * identity: one ridable, one not.
 *
 * <h2>What Elegron was describing</h2>
 *
 * <p>This was reported precisely enough on 27&ndash;28 September to identify without guessing:
 * "the crystal teleports the fake dragon, but only the real one can be ridden", "the crystal will
 * sometimes be tagged to one and sometimes tagged to the other", and "Could not find a dragon bound
 * to this crystal". An Ice and Fire summoning crystal resolves its dragon <b>by UUID</b>. For it to
 * find either of two entities, both must hold the same UUID. Zowner's account matches from the other
 * end: invisible dragons that make noise, block building where they stand, and that you get "one
 * every time the horn is used".
 *
 * <h2>The defect in 1.9.1</h2>
 *
 * <p>Capture ({@code ItemDragonHornStatic.itemInteractionForEntity}) stores the dragon with
 * {@code Entity.writeToNBT}, the <i>full</i> write, which includes {@code UUIDMost} and
 * {@code UUIDLeast}. Release ({@code ItemDragonHornActive.onPlayerStoppedUsing}) then rebuilds the
 * fire-dragon branch with {@code Entity.readFromNBT}, the full read, which restores that UUID.
 * (The ice-dragon branch uses {@code readEntityFromNBT} and does not, which is why no ice dragon
 * appears anywhere in this server's duplicate data.)
 *
 * <p>So the horn hands the released dragon the original's identity. That is harmless only while the
 * original is reliably gone &mdash; and capture ends with a bare {@code setDead()}, which does not
 * take effect until the entity is next ticked. Anything that intervenes before that tick (a crash,
 * a relog, a dimension change, the chunk unloading, ordinary lag) leaves the original alive while the
 * item holds a copy of it. Upstream has that failure reported repeatedly: AlexModGuy/Ice_and_Fire
 * issues #340 (duplication on death and relog "because of server lag"), #1561, #1978 and #2573.
 *
 * <h2>Upstream's own fix, backported</h2>
 *
 * <p>The live 1.12.2 branch ({@code 1.8.4-1.12.2}, which carries work past the 1.9.1 we run) solves
 * it by never storing the UUID at all: capture writes with {@code writeEntityToNBT}, the
 * subclass-only write, so a released dragon is always a <i>new</i> identity. The 2.x line for later
 * Minecraft versions went the other way and restores the UUID deliberately from its own
 * {@code EntityUUID} tag, but only alongside a checked spawn &mdash; a much larger change. For
 * 1.12.2 the correct fix is the 1.12.2 one.
 *
 * <p>Rather than rewrite the item, this removes {@code UUIDMost} and {@code UUIDLeast} from the
 * stored tag just before the release reads it. {@code Entity.readFromNBT} guards that read with
 * {@code compound.hasUniqueId("UUID")}, and {@code NBTTagCompound.hasUniqueId} tests for exactly
 * those two keys, so with them absent the restore is a no-op and the dragon keeps the fresh UUID it
 * was constructed with. Same outcome as upstream, reached without touching either branch of the
 * release logic.
 *
 * <p><b>Doing it at release rather than capture is deliberate</b>, and is the one place this improves
 * on a straight port: it also repairs horns that are <i>already filled</i> and sitting in players'
 * inventories and chests. A capture-side fix would only help dragons stored from then on, leaving
 * every existing loaded horn as a live ghost-dragon generator.
 *
 * <p><b>The trade-off, stated plainly.</b> A dragon released from a horn now has a new UUID, so a
 * summoning crystal bound to it before it was stored will no longer find it and has to be re-bound.
 * That is upstream's accepted behaviour for this version, and it is the right side of the trade:
 * re-binding a crystal is an inconvenience, while a ghost dragon is unkillable, unridable, blocks
 * building, and cannot be cleared without the horn that created it.
 *
 * <p>With this and register B15 in place the horn no longer manufactures duplicates, which is what
 * it was banned for.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.item.ItemDragonHornActive", remap = false)
public abstract class MixinItemDragonHornActive {

    @Unique
    private static long mtmixins$stripped = 0L;

    /**
     * Both names, because {@code onPlayerStoppedUsing} is inherited from vanilla {@code Item} and is
     * {@code func_77615_a} at runtime, while a {@code @Pseudo} mixin gets no help from the refmap.
     * See the dual-name section of the README.
     */
    @Inject(method = {"onPlayerStoppedUsing", "func_77615_a"}, at = @At("HEAD"), remap = false)
    private void mtmixins$dropStoredDragonUuid(ItemStack stack, World worldIn,
                                               EntityLivingBase entityLiving, int timeLeft,
                                               CallbackInfo ci) {
        if (stack == null) {
            return;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasUniqueId("UUID")) {
            return;
        }
        tag.removeTag("UUIDMost");
        tag.removeTag("UUIDLeast");

        long n = ++mtmixins$stripped;
        if (n == 1L || n % 50L == 0L) {
            MedievalTimesMixins.LOG.info(
                    "Dropped the stored UUID from a dragon horn before release, so the dragon comes "
                            + "back as a new entity instead of a second copy of the old one ({} so far). "
                            + "A summoning crystal bound to it will need re-binding. See register B16.", n);
        }
    }
}
