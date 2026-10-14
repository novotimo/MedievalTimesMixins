package com.novotimo.mtmixins.mixin.corpse;

import com.mojang.authlib.GameProfile;
import de.maxhenkel.corpse.commands.HistoryCommand;
import de.maxhenkel.corpse.net.MessageRequestDeathHistory;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.PlayerNotFoundException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Register B29 &mdash; {@code /deathhistory <player>} only worked for players who were online.
 *
 * <p>Corpse resolves the argument with vanilla's {@code CommandBase.getPlayer}, which tries a selector,
 * then a UUID, then a name, all against the online player list, and otherwise answers "That player
 * cannot be found". Every death record is on disk under the player's UUID regardless, so the history
 * of anyone offline &mdash; the usual case for a lost-items ticket &mdash; was unreachable, UUID or not.
 *
 * <p>Resolves the argument to a UUID instead: a UUID as given; an online player by name; otherwise the
 * server's username cache ({@code usercache.json}). That last step is
 * {@code PlayerProfileCache.getGameProfileForUsername}, the same lookup vanilla's {@code /op} and
 * {@code /whitelist add} use for offline names: it asks Mojang only if the name is not cached or its
 * entry is more than a month old. Selectors ({@code @p} and the like) and wrong argument counts are
 * left to Corpse. Runs only after the command's permission check, which this does not touch.
 */
@Mixin(value = HistoryCommand.class, remap = false)
public abstract class MixinHistoryCommandOffline {

    // HistoryCommand overrides CommandBase.execute, so its name in the shipped jar is the SRG one.
    @Inject(method = "func_184881_a(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/command/ICommandSender;[Ljava/lang/String;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$historyOfOfflinePlayers(MinecraftServer server, ICommandSender sender, String[] args,
                                                  CallbackInfo ci) throws CommandException {
        if (args.length != 1 || args[0].startsWith("@")) {
            return;
        }
        String target = args[0];
        UUID owner = null;
        try {
            owner = UUID.fromString(target);
        } catch (IllegalArgumentException notAUuid) {
            EntityPlayerMP online = server.getPlayerList().getPlayerByUsername(target);
            if (online != null) {
                owner = online.getUniqueID();
            } else {
                GameProfile cached = server.getPlayerProfileCache().getGameProfileForUsername(target);
                if (cached != null) {
                    owner = cached.getId();
                }
            }
        }
        if (owner == null) {
            throw new PlayerNotFoundException("commands.generic.player.notFound", target);
        }

        ci.cancel();
        if (sender.getCommandSenderEntity() instanceof EntityPlayerMP) {
            MessageRequestDeathHistory.sendDeathHistory((EntityPlayerMP) sender.getCommandSenderEntity(), owner);
        }
    }
}
