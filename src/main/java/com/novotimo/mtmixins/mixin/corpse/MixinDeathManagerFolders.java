package com.novotimo.mtmixins.mixin.corpse;

import com.novotimo.mtmixins.util.CorpseDeaths;
import de.maxhenkel.corpse.Death;
import de.maxhenkel.corpse.DeathManager;
import net.minecraft.entity.player.EntityPlayerMP;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

/**
 * Register B29 &mdash; Corpse's death history lists only the deaths filed in the world the asking player
 * is standing in. ForgeEssentials' Spawn world has its own deaths folder, so a death in Spawn was listed
 * only from inside Spawn and every other death only from outside it ("my death isn't in the list"), and
 * the Items button could not be reached for the missing ones. Lists every deaths folder instead; see
 * {@link CorpseDeaths}.
 *
 * <p>Only the {@code (EntityPlayerMP, UUID)} overload, the one the history uses. The
 * {@code (EntityPlayerMP)} and {@code (EntityPlayerMP, EntityPlayerMP)} overloads each call themselves
 * unconditionally in the shipped jar; nothing calls them, so they are left alone.
 *
 * <p>Fails soft: if listing goes wrong, Corpse's own listing runs.
 */
@Mixin(value = DeathManager.class, remap = false)
public abstract class MixinDeathManagerFolders {

    @Inject(method = "getDeaths(Lnet/minecraft/entity/player/EntityPlayerMP;Ljava/util/UUID;)Ljava/util/List;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void mtmixins$listEveryFolder(EntityPlayerMP context, UUID owner,
                                                 CallbackInfoReturnable<List<Death>> cir) {
        List<Death> deaths = CorpseDeaths.list(context, owner);
        if (deaths != null) {
            cir.setReturnValue(deaths);
        }
    }
}
