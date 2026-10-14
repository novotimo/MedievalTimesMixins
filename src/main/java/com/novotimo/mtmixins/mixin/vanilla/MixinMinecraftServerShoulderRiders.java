package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.ShoulderRiders;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Register B27, second half &mdash; a baby dragon on a player's shoulder when the server stops is not
 * saved anywhere it will be read back.
 *
 * <p>{@code MinecraftServer.stopServer} saves the players, asks each connection to disconnect, then
 * saves the worlds. The disconnect does not log anyone out first: it runs on the server thread while
 * the channel is still open, so {@code NetworkManager.handleDisconnection} does nothing, and no
 * "left the game" line appears between "Saving players" and "Saving worlds" in any of prod's logs.
 * The players are still in their worlds when the chunks are written, a dragon riding one is skipped
 * by {@code Entity.writeToNBTOptional}, and the copy in the player file's {@code Passengers} tag is
 * never loaded. So the 04:00 restart deletes every dragon that was on a shoulder at the time, and a
 * crash does the same, because {@code MinecraftServer.run} calls {@code stopServer} from its
 * {@code finally}.
 *
 * <p>This sets every rider down beside its player at the head of {@code stopServer}, before either
 * save. The player file then has no passengers and the chunk has the dragon, so it is there when its
 * owner logs back in and there is no second copy to duplicate. Ice and Fire's own logout handler does
 * the same thing for an ordinary logout; this covers the logout that shutdown skips.
 *
 * <p>Runs on whatever thread calls {@code stopServer}, which is the server thread for a {@code stop}
 * and the shutdown hook for a SIGTERM. The second, redundant call from the shutdown hook finds
 * nothing left to do.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServerShoulderRiders {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void mtmixins$setDownRidersBeforeSave(CallbackInfo ci) {
        PlayerList players = ((MinecraftServer) (Object) this).getPlayerList();
        if (players == null) {
            return;
        }
        for (EntityPlayerMP player : new ArrayList<>(players.getPlayers())) {
            if (player.isBeingRidden()) {
                ShoulderRiders.setDown(player, "before the server saved on shutdown");
            }
        }
    }
}
