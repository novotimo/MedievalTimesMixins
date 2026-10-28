package com.novotimo.mtmixins.mixin.iceandfire;

import com.github.alexthe666.iceandfire.entity.EntitySeaSerpent;
import com.novotimo.mtmixins.bridge.DragonBridge;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B50 &mdash; a sea serpent no longer breaks blocks inside a claim.
 *
 * <h2>The fault</h2>
 *
 * <p>{@code EntitySeaSerpent.breakBlock} destroys the plants and leaves in the serpent's bounding box
 * (when {@code Sea Serpent Griefing} is on) with {@code World.destroyBlock}, and posts no event: not
 * Forge's mob-griefing event and not Ice and Fire's own {@code GenericGriefEvent}, which every other Ice
 * and Fire griefer posts and Civilizations' {@code IafGriefListener} already cancels in claims. So a
 * serpent was the one Ice and Fire mob that could still change blocks in a town (triage B-07).
 *
 * <h2>The fix</h2>
 *
 * <p>The single {@code destroyBlock} call in {@code breakBlock} (checked with {@code javap}) asks
 * {@link DragonBridge#mobBlockChangeAllowed} first, the same per-block policy dragons' breath goes through:
 * refused in a claim unless the town opted in with {@code /town toggle mobs}; allowed during a raid on
 * that town once the block is journalled, so the restore puts it back. Outside claims nothing changes, so
 * the config switch stays on and serpents still clear plants and leaves in their way elsewhere.
 *
 * <p>Server only: {@code breakBlock} breaks only off the client. Ice and Fire is not obfuscated, so the
 * target is written in SRG with {@code remap = false}. Lives in {@code mixins.mtmixins.seaserpent.json},
 * so it can be switched off in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Mixin(value = EntitySeaSerpent.class, remap = false)
public abstract class MixinSeaSerpentBreak {

    @Redirect(method = "breakBlock",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/World;func_175655_b(Lnet/minecraft/util/math/BlockPos;Z)Z"),
            remap = false)
    private boolean mtmixins$gateSerpentBreak(World world, BlockPos pos, boolean dropBlock) {
        if (!DragonBridge.mobBlockChangeAllowed(world, pos, this)) {
            // false is what destroyBlock returns when nothing was broken, which is the truth.
            return false;
        }
        return world.destroyBlock(pos, dropBlock);
    }
}
