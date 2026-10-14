package com.novotimo.mtmixins.mixin.corpse;

import de.maxhenkel.corpse.entities.EntityCorpse;
import de.maxhenkel.corpse.gui.GuiHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B32 &mdash; the client half of {@link MixinGuiHandlerClicked}. The client builds its loot
 * window from the first body in the block too, so with two bodies on one block it could show the other
 * one's name and page count. Uses the body under the crosshair, which is the one the player clicked.
 * Cosmetic: the slots are filled from the server either way. Players get it with the pack.
 *
 * <p>Client only, in {@code mixins.mtmixins.corpseloot.json}.
 */
@Mixin(value = GuiHandler.class, remap = false)
public abstract class MixinGuiHandlerClickedClient {

    @Inject(method = "getCorpse(Lnet/minecraft/world/World;III)Lde/maxhenkel/corpse/entities/EntityCorpse;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void mtmixins$corpseUnderCrosshair(World world, int x, int y, int z,
                                                      CallbackInfoReturnable<EntityCorpse> cir) {
        if (!world.isRemote) {
            return;
        }
        RayTraceResult hit = Minecraft.getMinecraft().objectMouseOver;
        Entity entity = hit == null ? null : hit.entityHit;
        if (entity instanceof EntityCorpse && entity.world == world && !entity.isDead
                && entity.getPosition().equals(new BlockPos(x, y, z))) {
            cir.setReturnValue((EntityCorpse) entity);
        }
    }
}
