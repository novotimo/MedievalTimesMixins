package com.novotimo.mtmixins.mixin.ancientwarfare;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.novotimo.mtmixins.bridge.RaidBridge;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B1 &mdash; reproduces the entire hand-edited AncientWarfare jar.
 *
 * <p>The deployed jar
 * ({@code ancientwarfare-2.9.21-TownyRaidCompat-v2-FTBOnlyWORKING.jar}) differs from pristine
 * AncientWarfare in exactly one class, {@code vehicle.missiles.Ammo}, by three inserted calls
 * into {@code com.townyforge.compat.RaidPatchHooks}:
 *
 * <ol>
 *   <li>{@code breakBlockAndDrop} &mdash; a guard at the top: {@code if (!beforeAwBreak(world, pos)) return;}</li>
 *   <li>{@code breakBlockAndDrop} &mdash; {@code finishAwDirectBreak()} immediately after the
 *       {@code BlockTools.breakBlockAndDrop} call returns</li>
 *   <li>{@code setBlockToLava} &mdash; {@code captureRaidTerrainMutation(world, pos.up())} just
 *       before the first air check in the descent loop</li>
 * </ol>
 *
 * <p>Note the 2.9.20-based jar only had the first two. The third was added later and would have
 * been missed by diffing the older baseline, so this was taken from the jar actually running.
 *
 * <p><b>Why this is in the {@code server} list.</b> {@code Ammo} exists on the client too, but
 * TownyForge ships as a server-only jar, so {@link RaidBridge} can never resolve anything
 * client-side and the patch would be a guaranteed no-op there. Applying it anyway would only
 * add a way for a client to fail at boot if the pack's AncientWarfare version ever changes
 * {@code Ammo}. Singleplayer loses nothing: there is no TownyForge in singleplayer to enforce
 * claims against.
 *
 * <p>{@code remap = false} at class level because AncientWarfare is not obfuscated. The one
 * {@code @At} that targets a vanilla member re-enables it explicitly.
 */
@Pseudo
@Mixin(targets = "net.shadowmage.ancientwarfare.vehicle.missiles.Ammo", remap = false)
public abstract class MixinAmmo {

    /**
     * Hook 1. Cancelling here skips the whole method, which is what {@code return} did in the
     * edited jar. Fails open when TownyForge is absent.
     */
    @Inject(method = "breakBlockAndDrop", at = @At("HEAD"), cancellable = true)
    private void mtmixins$raidBreakGuard(World world, BlockPos pos, CallbackInfo ci) {
        if (!RaidBridge.beforeAwBreak(world, pos)) {
            ci.cancel();
        }
    }

    /**
     * Hook 2. The edited jar called this between the invoke and the {@code pop} of its boolean
     * result. Wrapping the operation reproduces the ordering without having to reason about
     * what is on the stack. {@code BlockTools.breakBlockAndDrop} is static, so there is no
     * receiver parameter.
     */
    @WrapOperation(
            method = "breakBlockAndDrop",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/shadowmage/ancientwarfare/core/util/BlockTools;"
                            + "breakBlockAndDrop(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;)Z"
            )
    )
    private boolean mtmixins$raidBreakFinish(World world, BlockPos pos, Operation<Boolean> original) {
        boolean broke = original.call(world, pos);
        RaidBridge.finishAwDirectBreak();
        return broke;
    }

    /**
     * Hook 3. The capture went in before the {@code isAirBlock(pos)} test inside the descent
     * loop, and it reports {@code pos.up()} &mdash; where the lava would actually go, not the
     * solid block that was found. Wrapping that first air check is the cleanest way to get both
     * {@code world} and the loop-local {@code pos} without capturing locals by index.
     *
     * <p>{@code ordinal = 0} matters: the method tests {@code isAirBlock} twice, on {@code pos}
     * and then on {@code pos.up()}. {@code remap = true} because this one is a vanilla member
     * and needs the refmap to become {@code func_175623_d} in production.
     */
    @WrapOperation(
            method = "setBlockToLava",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;isAirBlock(Lnet/minecraft/util/math/BlockPos;)Z",
                    ordinal = 0,
                    remap = true
            )
    )
    private boolean mtmixins$raidLavaCapture(World world, BlockPos pos, Operation<Boolean> original) {
        RaidBridge.captureRaidTerrainMutation(world, pos.up());
        return original.call(world, pos);
    }
}
