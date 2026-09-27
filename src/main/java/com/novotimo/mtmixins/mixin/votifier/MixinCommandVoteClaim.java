package com.novotimo.mtmixins.mixin.votifier;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Register B4 &mdash; "ClaimAnyDimension". Reproduces the hand-edited Votifier jar.
 *
 * <p>Upstream {@code CommandVoteClaim.execute} fetches the reward store from whichever world the
 * player is standing in:
 *
 * <pre>
 * RewardStoreWorldSavedData wsd = RewardStoreWorldSavedData.get(playerMP.getServerWorld());
 * </pre>
 *
 * so outstanding vote rewards were scoped per dimension: vote, walk through a portal, and your
 * rewards are unreachable. The edited jar called the no-arg {@code get()} overload instead, which
 * is
 *
 * <pre>
 * return get(FMLCommonHandler.instance().getMinecraftServerInstance().getEntityWorld());
 * </pre>
 *
 * i.e. always the overworld. <b>Both overloads already exist upstream</b> &mdash; the member lists
 * of the pristine and edited classes are identical, so nothing was added.
 *
 * <p>Rather than redirect the {@code get(World)} call (whose handler would have to name Votifier's
 * own return type, dragging the mod onto this project's compile classpath), this changes the
 * <em>argument</em>: the player's world becomes the overworld before it is passed in. Same
 * resulting store, and every type involved is vanilla.
 *
 * <p>Note the call site is {@code execute}, not {@code checkPermission} &mdash; upstream's
 * {@code checkPermission} is just {@code return true}.
 */
@Pseudo
@Mixin(targets = "com.github.upcraftlp.votifier.command.CommandVoteClaim", remap = false)
public abstract class MixinCommandVoteClaim {

    // Both names, because `execute` is inherited from vanilla CommandBase and is therefore
    // obfuscated at runtime, while this class has remap = false (correct: Votifier itself is not
    // obfuscated). The refmap cannot help - a @Pseudo mixin gives the annotation processor no
    // resolvable target class, so it cannot map an inherited vanilla method name. Listing both
    // lets Mixin match `execute` in a dev run and `func_184881_a` in production. Getting this
    // wrong fails at boot with "could not find any targets matching 'execute'".
    @ModifyExpressionValue(
            method = {"execute", "func_184881_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/EntityPlayerMP;getServerWorld()Lnet/minecraft/world/WorldServer;",
                    remap = true
            )
    )
    private WorldServer mtmixins$claimFromOverworldStore(WorldServer playersWorld) {
        if (playersWorld == null) {
            return null;
        }
        MinecraftServer server = playersWorld.getMinecraftServer();
        if (server == null) {
            return playersWorld;
        }
        WorldServer overworld = server.getWorld(0);
        return overworld != null ? overworld : playersWorld;
    }
}
