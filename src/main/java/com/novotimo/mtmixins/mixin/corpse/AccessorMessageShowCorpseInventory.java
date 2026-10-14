package com.novotimo.mtmixins.mixin.corpse;

import de.maxhenkel.corpse.net.MessageShowCorpseInventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.UUID;

/**
 * Register B29 &mdash; reads the two private fields of the message being handled. {@code execute} runs
 * on Corpse's single handler instance, not on the message, so {@code @Shadow} fields on
 * {@code MixinMessageShowCorpseInventory} would read the handler's own (always null) copies.
 */
@Mixin(value = MessageShowCorpseInventory.class, remap = false)
public interface AccessorMessageShowCorpseInventory {

    @Accessor(value = "playerUUID", remap = false)
    UUID mtmixins$playerUUID();

    @Accessor(value = "deathID", remap = false)
    UUID mtmixins$deathID();
}
