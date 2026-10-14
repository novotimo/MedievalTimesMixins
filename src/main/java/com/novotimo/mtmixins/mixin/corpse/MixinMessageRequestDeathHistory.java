package com.novotimo.mtmixins.mixin.corpse;

import com.novotimo.mtmixins.util.CorpseDeaths;
import de.maxhenkel.corpse.net.MessageRequestDeathHistory;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Register B29 &mdash; remembers whose death history each player was last sent. Every path to a history
 * list goes through this method: a player's own (the history key) and {@code /deathhistory <player>},
 * which only runs once the command's permission check has passed. {@code MixinMessageShowCorpseInventory}
 * then opens someone else's items only for a viewer who was sent that person's list, so it needs no
 * permission check of its own and follows whatever ForgeEssentials or op level decided for the command.
 */
@Mixin(value = MessageRequestDeathHistory.class, remap = false)
public abstract class MixinMessageRequestDeathHistory {

    @Inject(method = "sendDeathHistory(Lnet/minecraft/entity/player/EntityPlayerMP;Ljava/util/UUID;)V",
            at = @At("HEAD"), remap = false)
    private static void mtmixins$recordHistorySent(EntityPlayerMP playerToSend, UUID playerUUID, CallbackInfo ci) {
        CorpseDeaths.historySent(playerToSend, playerUUID);
    }
}
