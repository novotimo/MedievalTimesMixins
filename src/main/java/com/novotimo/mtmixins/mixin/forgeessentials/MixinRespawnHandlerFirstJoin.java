package com.novotimo.mtmixins.mixin.forgeessentials;

import com.forgeessentials.commons.selections.WarpPoint;
import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B24 &mdash; a brand-new player's first session is broken: Custom NPCs at spawn vanish or
 * flicker and their trade windows do nothing until the player relogs.
 *
 * <h2>The bug</h2>
 *
 * <p>Every new player is created in the overworld. ForgeEssentials' spawn is in its Spawn world
 * (dimension 10), so {@code RespawnHandler.playerLoadFromFile} sees a player with no playerdata file
 * whose spawn is in another dimension and queues them; {@code doFirstRespawn} then transfers them
 * to dimension 10 from {@code EntityJoinWorldEvent}. That event fires from <i>inside</i>
 * {@code World.spawnEntity} in the overworld, while {@code PlayerList.playerLoggedIn} is still adding
 * the player. When the transfer returns, {@code spawnEntity} carries on: it files the player into
 * the overworld chunk it worked out before the event, {@code Chunk.addEntity} sees that the player
 * is no longer in that chunk, logs
 *
 * <pre>
 *   Wrong location! (-39, 154) should be (-3, -4), EntityPlayerMP['...'/..., l='Spawn', x=-623.42, ...]
 * </pre>
 *
 * <p>and calls {@code setDead()} on the player. Nothing clears that flag until the player changes
 * dimension or reconnects. For the whole first session {@code WorldServer.tickPlayers} skips
 * updating the player and calls {@code onEntityRemoved} on them every tick, which untracks them from
 * every entity: the client is told to destroy everything around it, stationary NPCs never come back,
 * and with the player never ticked an open trade container never syncs. Relogging creates a new
 * player object that is already saved in dimension 10, which is why "relog" worked.
 *
 * <h2>Evidence</h2>
 *
 * <p>Every first join in prod's logs from 2026-10-04 to 2026-10-09 (darkrelm39, N1ghTmAREx10,
 * Eternal_697, Denis_SG, ...) logs in at the overworld spawn (about -48, 73, -64), logs exactly that
 * "Wrong location!" line in the same tick, and disconnects within 6 to 75 seconds.
 *
 * <h2>The fix</h2>
 *
 * <p>Moves the new player into the spawn dimension during {@code LoadFromFile} itself, before
 * vanilla has picked a world for them. Vanilla then logs them straight into dimension 10, exactly as
 * it does a returning player saved there, and there is no transfer in the middle of the login.
 * ForgeEssentials still registers dimension 10 with the client during the handshake, before the
 * login packet, so the client handles it the same way it does for every returning player.
 *
 * <p>The method compares {@code player.dimension}, read <i>before</i> this call, with the value
 * returned here. Returning that same old dimension sends ForgeEssentials down its own same-dimension
 * branch, so it places the player at the spawn point with its own coordinates, yaw and pitch.
 *
 * <p>Fails soft: if the spawn dimension is not loaded, nothing changes and ForgeEssentials keeps its
 * stock behaviour. Server only (ForgeEssentials is not in the pack). {@code @Pseudo}, so a server
 * without ForgeEssentials is unaffected. Its own config file,
 * {@code mixins.mtmixins.firstjoin.json}, so it can be switched off in
 * {@code config/mixinbooter.cfg} without a rebuild.
 */
@Pseudo
@Mixin(targets = "com.forgeessentials.core.misc.RespawnHandler", remap = false)
public abstract class MixinRespawnHandlerFirstJoin {

    @Redirect(method = "playerLoadFromFile",
            at = @At(value = "INVOKE",
                    target = "Lcom/forgeessentials/commons/selections/WarpPoint;getDimension()I",
                    remap = false),
            remap = false)
    private int mtmixins$spawnInSpawnDimension(WarpPoint spawn, PlayerEvent.LoadFromFile event) {
        int spawnDimension = spawn.getDimension();
        EntityPlayer player = event.getEntityPlayer();
        int createdIn = player.dimension;
        if (createdIn == spawnDimension || DimensionManager.getWorld(spawnDimension) == null) {
            return spawnDimension;
        }

        player.dimension = spawnDimension;
        MedievalTimesMixins.LOG.info(
                "New player {} logs in directly to dimension {}, the ForgeEssentials spawn, rather than "
                        + "being moved there mid-login from dimension {}. Register B24.",
                player.getName(), spawnDimension, createdIn);
        return createdIn;
    }
}
