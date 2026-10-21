package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.MisplacedEntities;

import net.minecraft.entity.Entity;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B38, first half &mdash; notes each entity vanilla kills for being loaded into the wrong chunk.
 *
 * <p>{@code Chunk.addEntity} warns {@code Wrong location!} and calls {@code setDead()} when an entity's
 * position is outside the chunk it is being added to; that call is the only {@code setDead} in the method.
 * This runs just before it and records the entity in {@link MisplacedEntities}. Vanilla's behaviour is
 * unchanged. The second half, {@code iceandfire.MixinDragonSaveMisplaced}, reads the record.
 */
@Mixin(Chunk.class)
public abstract class MixinChunkMisplacedEntity {

    @Inject(method = "addEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;setDead()V"))
    private void mtmixins$noteMisplaced(Entity entityIn, CallbackInfo ci) {
        MisplacedEntities.mark(entityIn);
    }
}
