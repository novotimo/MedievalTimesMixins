package com.novotimo.mtmixins.mixin.corpse;

import com.novotimo.mtmixins.util.CorpseLoot;
import de.maxhenkel.corpse.entities.EntityCorpse;
import de.maxhenkel.corpse.gui.GuiHandler;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B32 &mdash; Corpse opens a loot window by block position and takes the first body in that
 * block, so a second body on the same block (dying twice in one place) could never be opened. On the
 * server the window now opens on the body that was clicked, which {@link MixinEntityCorpseLoot} records
 * for the length of the click. Anything else that asks keeps Corpse's answer.
 *
 * <p>Server only, in {@code mixins.mtmixins.corpseloot.json}.
 */
@Mixin(value = GuiHandler.class, remap = false)
public abstract class MixinGuiHandlerClicked {

    @Inject(method = "getCorpse(Lnet/minecraft/world/World;III)Lde/maxhenkel/corpse/entities/EntityCorpse;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void mtmixins$clickedCorpse(World world, int x, int y, int z,
                                               CallbackInfoReturnable<EntityCorpse> cir) {
        EntityCorpse clicked = CorpseLoot.clickedAt(world, x, y, z);
        if (clicked != null) {
            cir.setReturnValue(clicked);
        }
    }
}
