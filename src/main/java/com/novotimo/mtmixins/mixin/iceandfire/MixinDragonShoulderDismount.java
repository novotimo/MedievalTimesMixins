package com.novotimo.mtmixins.mixin.iceandfire;

import com.github.alexthe666.iceandfire.entity.EntityDragonBase;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B28 &mdash; a baby dragon carried on the shoulder drops back where it was picked up when
 * its owner presses shift, instead of beside them. An Ice and Fire 1.9.1 bug; upstream fixed it after
 * that release and never shipped the fix for 1.12.2.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code EntityDragonBase.updateRiding} gets a carried dragon off its player when
 * {@code getControlState() == 16}, the "dismount" bit. Only the owner's client sets that bit, while the
 * dragon is on its shoulder and shift is held, and sends it to the server in a
 * {@code MessageDragonControl}. The server applies the message only while the dragon is still riding
 * that player. So once the server has dismounted the dragon, the release of shift is never sent and
 * the server's copy of the bit stays at 16. It is not saved, so it lasts until the dragon is unloaded.
 *
 * <p>The first carry after the dragon loads works. Every carry after that:
 *
 * <ol>
 *   <li>Right click: both sides mount the dragon. On the server's next tick {@code updateRiding} reads
 *   the stale 16 and dismounts it at once, beside the player, i.e. where it was picked up.</li>
 *   <li>The owner's client clears the bit and tells the server, a tick too late: the dragon is no
 *   longer riding, so the message is dropped and the bit stays stuck. The client still thinks it is
 *   carrying the dragon and draws it on the shoulder, since a player is never told about changes to
 *   its own passengers. Everyone else sees it on the ground.</li>
 *   <li>Shift: the client dismounts its copy, which then follows the server's position updates back
 *   to the pickup spot. That is the reported "dropped exactly where it was picked up". In between,
 *   the dragon the owner thought they were carrying was never with them.</li>
 * </ol>
 *
 * <h2>Upstream's fix, backported</h2>
 *
 * <p>The {@code 1.8.4-1.12.2} branch fixed this on 1 June 2020 in 7429b19e, "fix baby dragons
 * teleporting after riding", by testing {@code riding.isSneaking()} instead of the control bit. The
 * server knows whether a player is sneaking from vanilla's own entity-action packet, so it dismounts
 * at the same moment as the client, and no stale state is left behind. This applies the same
 * condition to the server's copy by answering the one {@code getControlState()} call in
 * {@code updateRiding} with 16 while the player sneaks and 0 otherwise. The elytra half of the test is
 * untouched.
 *
 * <p>Server only: the client's own bit is set and cleared from the shift key in the same tick, so
 * its copy already behaves, and players need no new pack. Its own config file,
 * {@code mixins.mtmixins.shoulderdismount.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.EntityDragonBase", remap = false)
public abstract class MixinDragonShoulderDismount {

    /** Read out of the shipped 1.9.1 jar with javap: the only {@code getControlState} in the method. */
    @Redirect(
            method = "updateRiding",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/alexthe666/iceandfire/entity/EntityDragonBase;getControlState()B"
            ),
            remap = false
    )
    private byte mtmixins$dismountWhenSneaking(EntityDragonBase dragon, Entity riding) {
        return riding.isSneaking() ? (byte) 16 : (byte) 0;
    }
}
