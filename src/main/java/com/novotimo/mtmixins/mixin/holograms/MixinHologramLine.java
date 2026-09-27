package com.novotimo.mtmixins.mixin.holograms;

import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B3 &mdash; replaces the hand-edited Holograms jar
 * ({@code Holograms-Forge-0.6.1-ENTITYID-PATCH.jar}), which differed from pristine in exactly one
 * class, identified by timestamp: {@code HologramLine} was the only entry dated 2026-09-07 in a jar
 * otherwise built 2021-12-20.
 *
 * <p>Hologram lines are packet-only fake entities: they are spawned on clients with
 * {@code SPacketSpawnObject} / {@code SPacketSpawnMob} and never exist server-side. Vanilla
 * assigns entity IDs from a shared counter, so a hologram line can be handed an ID that a real
 * entity also uses, which corrupts the receiving client's entity map. The patch moves them into a
 * descending negative range that nothing real occupies:
 *
 * <pre>
 * private static int ENTITY_ID = -1000000;
 * ...
 * entity.setEntityId(ENTITY_ID--);
 * </pre>
 *
 * <p>No pristine 0.6.1 was available to diff against, so this reproduces the patched behaviour
 * rather than a verified delta. That is acceptable here because the intent is unambiguous from the
 * patched code: there is no other reason to hand out IDs from &minus;1000000 downwards.
 *
 * <p>{@code server} side because Holograms ships as a server-only jar, so the class does not exist
 * on clients at all.
 *
 * <p>Unrelated but worth fixing in the same pass: this mod throws a
 * {@code NullPointerException} from {@code JsonHologramSaver.load} line 71 during
 * {@code onServerStarting}, which aborts server boot entirely. That is data, not code &mdash; most
 * likely a malformed entry in the holograms JSON.
 */
@Pseudo
@Mixin(targets = "com.envyful.holograms.forge.hologram.entity.HologramLine", remap = false)
public abstract class MixinHologramLine {

    /**
     * Descends, so successive lines never collide with each other either. Merged into the target's
     * static initialiser by Mixin.
     */
    @Unique
    private static int mtmixins$nextHologramEntityId = -1000000;

    @Shadow
    private Entity entity;

    /**
     * {@code TAIL} rather than {@code RETURN} so this runs once, after the rest of
     * {@code initEntity} has finished configuring the fake entity.
     */
    @Inject(method = "initEntity", at = @At("TAIL"), remap = false)
    private void mtmixins$assignOutOfBandEntityId(CallbackInfo ci) {
        if (this.entity != null) {
            this.entity.setEntityId(mtmixins$nextHologramEntityId--);
        }
    }
}
