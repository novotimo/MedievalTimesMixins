package com.novotimo.mtmixins.mixin.armourers;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register A7 &mdash; Armourer's Workshop looks up a class by name with no caching:
 *
 * <pre>
 * private static Class&lt;? extends EntityLivingBase&gt; getCNPCEntityClass() {
 *     try { return Class.forName("noppes.npcs.entity.EntityCustomNpc"); }
 *     catch (ClassNotFoundException e) { e.printStackTrace(); return null; }
 * }
 * </pre>
 *
 * It is reached from {@code ModCapabilityManager.onAttachEntityCapabilities}, which fires
 * for <em>every entity construction in the world</em>. That measured 3.97 ms/s on prod.
 * {@code Class.forName} on an already-loaded class still takes a classloader lock.
 *
 * <p><b>This is the pattern for mods that are on no maven.</b> {@code @Pseudo} plus a
 * string {@code targets} means the class never has to be on the compile classpath &mdash;
 * Mixin resolves it from the real jar at apply time, and quietly does nothing if the mod
 * is absent instead of crashing. {@code remap = false} because mod code is not
 * obfuscated, so there is nothing for the refmap to translate.
 */
@Pseudo
@Mixin(targets = "moe.plushie.armourers_workshop.common.addons.AddonCustomNPCS", remap = false)
public abstract class MixinAddonCustomNPCS {

    @Unique
    private static Class<?> mtmixins$cached;

    @Unique
    private static boolean mtmixins$resolved;

    @Inject(method = "getCNPCEntityClass", at = @At("HEAD"), cancellable = true, remap = false)
    private static void mtmixins$useCachedClass(CallbackInfoReturnable<Class<?>> cir) {
        if (!mtmixins$resolved) {
            try {
                mtmixins$cached = Class.forName("noppes.npcs.entity.EntityCustomNpc");
            } catch (ClassNotFoundException ignored) {
                // CustomNPCs absent. Upstream printed a stack trace here on every
                // entity spawn; returning null once and remembering it is enough.
                mtmixins$cached = null;
            }
            mtmixins$resolved = true;
        }
        cir.setReturnValue(mtmixins$cached);
    }
}
