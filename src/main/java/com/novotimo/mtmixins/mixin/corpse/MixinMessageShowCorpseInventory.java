package com.novotimo.mtmixins.mixin.corpse;

import com.novotimo.mtmixins.util.CorpseDeaths;
import de.maxhenkel.corpse.net.MessageShowCorpseInventory;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B29 &mdash; the Items button in Corpse's death history ({@code /deathhistory <player>}, or a
 * player's own history) shows nothing, and the server logs a {@code NullPointerException} at
 * {@code EntityCorpse.createFromDeath(EntityCorpse.java:51)}.
 *
 * <h2>The bug</h2>
 *
 * <p>Corpse 1.12.2-1.0.8 (the last 1.12.2 build), {@code MessageShowCorpseInventory.execute}, with
 * {@code player} the one who clicked and {@code p} the one whose history it is:
 *
 * <pre>
 *   p = player.world.getPlayerEntityByUUID(message.playerUUID)   // online, same world, or nothing
 *   death = DeathManager.getDeath(p, message.deathID)            // null on any read error
 *   CommonProxy.setDeathToShow(player, death)                    // stored under the viewer
 *   p.openGui("corpse", 1, ...)                                  // opened on the owner
 * </pre>
 *
 * <p>and GUI 1 builds its container from {@code getDeathToShow(p)}, looked up under the owner. So
 * staff looking at someone else's deaths always got null (or, worse, the owner's own last-viewed record
 * in a window on the owner's screen), and nothing at all happened if the owner was offline or in another
 * world. A player looking at their own deaths only failed when the file read failed, which
 * {@code getDeath} swallows into null. On top of that the record is read from the deaths folder of the
 * world the player is standing in, and ForgeEssentials' Spawn world has its own: see
 * {@link CorpseDeaths}.
 *
 * <h2>Evidence</h2>
 *
 * <p>Prod's logs, 4 to 14 October 2026: the NPE 119 times, 70 of them on 13 October. The trace
 * reported on 15 October runs {@code MessageShowCorpseInventory.execute:36 -> EntityPlayer.openGui ->
 * GuiHandler.getServerGuiElement:25 -> EntityCorpse.createFromDeath:51}, and line 51 is
 * {@code death.getPlayerUUID()}.
 *
 * <h2>The fix</h2>
 *
 * <p>Replaces the method: the record is found by owner and death ID in every deaths folder, whether or
 * not the owner is online, and the window opens on the player who clicked. Someone other than the owner
 * gets the window only if the server sent them that owner's history, which is the permission
 * {@code /deathhistory} already checked. A record that cannot be found tells the viewer so and logs
 * every folder searched; a record that cannot be read logs the exception and the file. Whether the
 * window is editable is unchanged: Corpse makes it editable in creative mode only.
 *
 * <p>Server only. Its own config file, {@code mixins.mtmixins.corpse.json}, so it can be switched off
 * in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(value = MessageShowCorpseInventory.class, remap = false)
public abstract class MixinMessageShowCorpseInventory {

    // The full descriptor, because javac also emitted a synthetic bridge execute(EntityPlayerMP, IMessage).
    @Inject(method = "execute(Lnet/minecraft/entity/player/EntityPlayerMP;Lde/maxhenkel/corpse/net/MessageShowCorpseInventory;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$showToViewer(EntityPlayerMP viewer, MessageShowCorpseInventory message, CallbackInfo ci) {
        ci.cancel();
        AccessorMessageShowCorpseInventory fields = (AccessorMessageShowCorpseInventory) message;
        CorpseDeaths.show(viewer, fields.mtmixins$playerUUID(), fields.mtmixins$deathID());
    }
}
