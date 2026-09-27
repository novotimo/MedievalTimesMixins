package com.novotimo.mtmixins.mixin.vanilla;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
            // The owner here is EntityItem, NOT Entity, even though pushOutOfBlocks is
            // declared on Entity. javac emits the *qualifying type* of the receiver as the
            // invoke owner, and the receiver is `this` inside EntityItem. Getting this wrong
            // is silent: Mixin scans zero call sites and the injection check fails at boot
            // with "Scanned 0 target(s)". Verified against the constant pool of the SRG jar:
            //   #174 = Methodref // net/minecraft/entity/item/EntityItem.func_145771_j:(DDD)Z
            at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/item/EntityItem;pushOutOfBlocks(DDD)Z")
    )
    // The receiver parameter must be typed to match the INVOKE owner above, so EntityItem
    // and not Entity, even though every field read below is inherited from Entity. Mismatch
    // is rejected at apply time with "unexpected argument type ... at index 0".
    private boolean mtmixins$throttlePushOutOfBlocks(EntityItem self, double x, double y, double z,
                                                     Operation<Boolean> original) {
        // Horizontal motion only, deliberately. onUpdate applies gravity BEFORE this call,
        // so a resting item always has motionY of about -0.04 here (move() cancels it
        // against the ground afterwards). Including motionY in the test makes speedSq
        // ~0.0016 and the throttle below never fires at all.
        //
        // Drag is multiplicative, so horizontal motion decays towards zero without ever
        // reaching it; an exact == 0 test would also never fire.
        final double horizontalSq = self.motionX * self.motionX
                + self.motionZ * self.motionZ;

        if (self.onGround && horizontalSq < 1.0E-6D) {
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
