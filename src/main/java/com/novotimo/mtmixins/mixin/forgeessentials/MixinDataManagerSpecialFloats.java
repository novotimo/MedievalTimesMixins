package com.novotimo.mtmixins.mixin.forgeessentials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B26 &mdash; ForgeEssentials crashes the server when it saves a player whose stored
 * location contains NaN.
 *
 * <h2>The bug</h2>
 *
 * <p>ForgeEssentials writes each player's {@code PlayerInfo} (home, last teleport origin, last
 * death location) to JSON on logout through {@code DataManager.getGson()}. Stock Gson refuses NaN
 * and infinity, so a single NaN coordinate throws {@code IllegalArgumentException: NaN is not a valid
 * double value as per JSON specification} out of {@code PlayerInfo.logout}. That runs inside
 * {@code PlayerLoggedOutEvent}, on the server thread, from the network tick: nothing catches it and
 * the server goes down.
 *
 * <h2>Evidence</h2>
 *
 * <p>crash-2026-10-13_19.50.07: ripmaxwell2013, already saved at NaN (see Register B25), "fell out of
 * the world" during a login at 19:50:06. ForgeEssentials stored that NaN death location, and the
 * logout a second later crashed the server in {@code DataManager.toJson}.
 *
 * <h2>The fix</h2>
 *
 * <p>Builds ForgeEssentials' Gson with {@code serializeSpecialFloatingPointValues()}, so NaN is
 * written as {@code NaN} rather than thrown. Reading it back already works: {@code Gson.fromJson}
 * parses leniently and accepts {@code NaN}. A NaN home or {@code /back} point that a player later
 * teleports to is caught by B25's per-tick player check, which moves them back to their last good
 * position.
 *
 * <p>Redirecting the one {@code GsonBuilder.create()} call in {@code getGson} leaves every other
 * builder setting and every registered type adapter as ForgeEssentials set them. Target written
 * with {@code remap = false} and read from the shipped jar with {@code javap}
 * ({@code invokevirtual com/google/gson/GsonBuilder.create:()Lcom/google/gson/Gson;}, offset 157).
 *
 * <p>Server only (ForgeEssentials is not in the pack). {@code @Pseudo}, so a server without
 * ForgeEssentials is unaffected. Its own config file, {@code mixins.mtmixins.fesave.json}, so it can
 * be switched off in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Pseudo
@Mixin(targets = "com.forgeessentials.data.v2.DataManager", remap = false)
public abstract class MixinDataManagerSpecialFloats {

    @Redirect(method = "getGson",
            at = @At(value = "INVOKE",
                    target = "Lcom/google/gson/GsonBuilder;create()Lcom/google/gson/Gson;",
                    remap = false),
            remap = false)
    private static Gson mtmixins$allowSpecialFloats(GsonBuilder builder) {
        return builder.serializeSpecialFloatingPointValues().create();
    }
}
