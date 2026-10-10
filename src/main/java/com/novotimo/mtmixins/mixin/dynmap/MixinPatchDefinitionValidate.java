package com.novotimo.mtmixins.mixin.dynmap;

import java.util.concurrent.atomic.AtomicLong;

import com.novotimo.mtmixins.MedievalTimesMixins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Register B22 &mdash; Dynmap's model-face check measures two of the wrong corners, so it throws
 * away faces that would render correctly and logs each one as FATAL "Invalid modellist patch".
 *
 * <h2>The bug</h2>
 *
 * <p>Dynmap turns every model face into a {@code PatchDefinition}: an origin {@code x0/y0/z0} plus
 * the ends of the U and V texture vectors, {@code xu/yu/zu} and {@code xv/yv/zv}, with the visible
 * part of the texture between {@code umin..umax} and {@code vmin..vmax}. {@code validate()} is meant
 * to reject a face whose visible corners fall outside the block, and Dynmap 3.6-818 computes them as
 *
 * <pre>
 *   xx0 = x0 + (xu - x0) * umin + (xv - x0) * vmin;   // P(umin, vmin)
 *   xx1 = x0 + (xu - x0) * vmin + (xv - x0) * vmax;   // P(vmin, vmax): should be P(umin, vmax)
 *   xx2 = x0 + (xu - x0) * umax + (xv - x0) * vmin;   // P(umax, vmin)
 *   xx3 = x0 + (xu - x0) * vmax + (xv - x0) * vmax;   // P(vmax, vmax): should be P(umax, vmax)
 * </pre>
 *
 * <p>and the same for y and z, rejecting the face if any value is outside [-1, 2]. The wrong pair
 * only matters when a face shows a texture region smaller than itself, because then the U vector is
 * long: a 16-pixel face showing a 1-pixel strip has a U vector 16 blocks long, so
 * {@code (xu - x0) * vmin} lands far outside the block although the face is entirely inside it.
 * Blockbench models do this constantly, which is why MineFantasy Reforged accounts for nearly all
 * of it.
 *
 * <h2>Evidence</h2>
 *
 * <p>Prod has logged 80,119 of these lines in the eleven seconds after every boot since
 * DynmapBlockScan was added on 2026-10-08 (12.1 MB of a 13.9 MB latest.log); 77,695 come from
 * MineFantasy Reforged. Every element face of MineFantasy's model JSONs was run through Dynmap
 * 3.6-818's own {@code updateModelFace} and {@code validate()}: 463 of 1,376 faces are rejected,
 * every one of them with its real corners inside the block, and they include all 284 distinct
 * box/side pairs in prod's log. The other ten mods in the log give the same result. Upstream's v3.0
 * branch still has this code; upstream has also dropped its Forge 1.12.2 module, so there is no
 * Dynmap build to upgrade to.
 *
 * <h2>The fix</h2>
 *
 * <p>Applies the same range check at the patch's real four corners. By construction in
 * {@code updateModelFace} those are the face's own corners, so a face now passes exactly when its
 * geometry is inside [-1, 2] blocks: the same -16..32 pixel limit vanilla's {@code BlockPart}
 * enforces on every model element. NaN behaves as before (stock passes it, so does this).
 *
 * <p>{@code validate()} has two other callers in {@code PatchDefinitionFactory}, plain patch
 * definitions and rotated copies, and this covers both. The rotated path drops a failing face
 * without logging anything, so faces of rotated block variants were being lost silently as well.
 *
 * <p>Server only: Dynmap is not in the pack. {@code @Pseudo}, so a server without Dynmap is
 * unaffected. Its own config file, {@code mixins.mtmixins.dynmap.json}, so it can be blacklisted
 * in {@code config/mixinbooter.cfg} without a rebuild.
 */
@Pseudo
@Mixin(targets = "org.dynmap.utils.PatchDefinition", remap = false)
public abstract class MixinPatchDefinitionValidate {

    @Shadow public double x0;
    @Shadow public double y0;
    @Shadow public double z0;
    @Shadow public double xu;
    @Shadow public double yu;
    @Shadow public double zu;
    @Shadow public double xv;
    @Shadow public double yv;
    @Shadow public double zv;
    @Shadow public double umin;
    @Shadow public double umax;
    @Shadow public double vmin;
    @Shadow public double vmax;

    /** Faces accepted here that stock {@code validate()} would have dropped. */
    @Unique
    private static final AtomicLong mtmixins$kept = new AtomicLong();

    @Inject(method = "validate", at = @At("HEAD"), cancellable = true)
    private void mtmixins$validateRealCorners(CallbackInfoReturnable<Boolean> cir) {
        boolean good = mtmixins$cornerInRange(umin, vmin) && mtmixins$cornerInRange(umax, vmin)
                && mtmixins$cornerInRange(umin, vmax) && mtmixins$cornerInRange(umax, vmax);
        // With the real corners in range, stock fails only on its two misplaced ones.
        if (good && !(mtmixins$cornerInRange(vmin, vmax) && mtmixins$cornerInRange(vmax, vmax))) {
            long kept = mtmixins$kept.incrementAndGet();
            if (mtmixins$isPowerOfTen(kept)) {
                // Deliberately does not quote Dynmap's own error text, so grepping for that error
                // (or filtering it in log4j) never matches this line.
                MedievalTimesMixins.LOG.info(
                        "Kept {} Dynmap model faces that stock Dynmap would have thrown away: its "
                                + "validate() measures two wrong corners. Register B22.",
                        kept);
            }
        }
        cir.setReturnValue(good);
    }

    /** The patch's point at texture coordinate (s, t), checked against Dynmap's own bounds. */
    @Unique
    private boolean mtmixins$cornerInRange(double s, double t) {
        return mtmixins$inRange(x0 + (xu - x0) * s + (xv - x0) * t)
                && mtmixins$inRange(y0 + (yu - y0) * s + (yv - y0) * t)
                && mtmixins$inRange(z0 + (zu - z0) * s + (zv - z0) * t);
    }

    /** Stock {@code outOfRange} inverted, NaN included: comparisons with NaN are false there too. */
    @Unique
    private static boolean mtmixins$inRange(double v) {
        return !(v < -1.0D || v > 2.0D);
    }

    @Unique
    private static boolean mtmixins$isPowerOfTen(long n) {
        while (n >= 10L && n % 10L == 0L) {
            n /= 10L;
        }
        return n == 1L;
    }
}
