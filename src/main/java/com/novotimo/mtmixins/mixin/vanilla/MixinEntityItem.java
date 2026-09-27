package com.novotimo.mtmixins.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Register A3 &mdash; ground items were 66 ms/s of a 1000 ms/s tick budget on prod, and
 * 27.75 of that was {@code pushOutOfBlocks}, which {@link EntityItem#onUpdate()} calls
 * unconditionally on every item on every tick. The call does a full block-collision
 * sweep whether or not the item is anywhere near a block it needs escaping from.
 *
 * <p>An item that is resting on the ground and not moving cannot have become newly
 * stuck since last tick, so the sweep only needs to run occasionally for it. Moving
 * items are untouched.
 *
 * <p>Note this is written against MCP names ({@code onUpdate}, {@code motionX}). The
 * annotation processor turns those into the SRG names present at runtime via the
 * refmap. Do not "helpfully" replace them with {@code func_} names.
 */
@Mixin(EntityItem.class)
public abstract class MixinEntityItem {

    /**
     * Mixin merges every member of this class into EntityItem, so anything added here
     * must not collide with a name the target or another mixin already uses.
     * {@code @Unique} makes Mixin enforce that, and the prefix makes it obvious in a
     * decompile which mod put it there.
     */
    @Unique
    private int mtmixins$restTicks;

    /**
     * {@code @WrapOperation} (from MixinExtras, shaded into MixinBooter) hands you the
     * original call as an {@link Operation} so you can decide whether to make it. It is
     * the clean way to express "conditionally skip this" &mdash; the older idiom was
     * {@code @Redirect}, which silently conflicts if two mods target the same call.
     */
    @WrapOperation(
            method = "onUpdate",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;pushOutOfBlocks(DDD)Z")
    )
    private boolean mtmixins$throttlePushOutOfBlocks(Entity self, double x, double y, double z,
                                                     Operation<Boolean> original) {
        // Vanilla applies drag multiplicatively, so motion decays towards zero but never
        // reaches it. An exact == 0 test would never fire.
        final double speedSq = self.motionX * self.motionX
                + self.motionY * self.motionY
                + self.motionZ * self.motionZ;

        if (self.onGround && speedSq < 1.0E-6D) {
            if (this.mtmixins$restTicks++ % 4 != 0) {
                // noClip is whatever the last real call set it to, which for a resting
                // item is still correct.
                return self.noClip;
            }
        } else {
            this.mtmixins$restTicks = 0;
        }

        return original.call(self, x, y, z);
    }
}
