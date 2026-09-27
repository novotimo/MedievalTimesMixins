package com.novotimo.mtmixins.mixin.infinitylib;

import com.novotimo.mtmixins.MedievalTimesMixins;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.vertex.VertexFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * Register B8 &mdash; one failed item render makes InfinityLib's tessellator throw for the rest of the
 * session, so the game becomes unplayable rather than drawing one item wrong.
 *
 * <h2>The mechanism</h2>
 *
 * <p>{@code TessellatorBakedQuad} is a {@code ThreadLocal} singleton with one piece of state,
 * {@code drawMode}, which starts at {@code DRAW_MODE_NOT_DRAWING} ({@code -1}):
 *
 * <ul>
 *   <li>{@code startDrawing(mode)} sets it, or throws
 *       {@code RuntimeException("ALREADY CONSTRUCTING VERTICES")} if it is already set.</li>
 *   <li>{@code onDrawCall()} (reached through {@code draw()}) clears the buffers and puts it back to
 *       {@code -1}, or throws {@code "NOT CONSTRUCTING VERTICES"} if it was not set.</li>
 * </ul>
 *
 * <p>Four places call {@code startDrawingQuads} and then {@code draw()} &mdash;
 * {@code BakedInfItemSubModel.getQuads}, {@code BakedInfBlockModel}, {@code BlockWithTileRenderer} and
 * {@code RenderUtilBase} &mdash; and <b>not one of them has a try/finally</b>. So if the render callback
 * between the two throws, {@code draw()} is never reached and the thread-local stays stuck in drawing
 * mode. Every later render of any InfinityLib-modelled item or block on that thread then throws
 * {@code ALREADY CONSTRUCTING VERTICES}, for ever, with a stack trace that points at the innocent
 * caller rather than the render that actually failed.
 *
 * <p>LoliASM is what turns that into a loop instead of a single crash: it carries VanillaFix's
 * crash-recovery, so the first exception writes a report and returns to the menu with the process, and
 * the poisoned {@code ThreadLocal}, still alive. Opening the inventory then crashes every single time
 * &mdash; which is exactly how this was reported, with JEI's ingredient list as the trigger because it
 * renders hundreds of item models on one screen.
 *
 * <h2>The fix</h2>
 *
 * <p>Make the state recoverable rather than terminal. One hook at the top of {@code startDrawing}:
 * if the tessellator is still marked as drawing, a previous render leaked, so discard its half-built
 * buffers and reset before letting the original method run. Doing it here covers all four leak sites
 * with one injector, which four separate try/finally wrappers would not do any better.
 *
 * <p>It deliberately does <b>not</b> call {@code onDrawCall()} to do the reset, tempting as that is:
 * {@code onDrawCall} also nulls {@code face} and {@code textureFunction}, and every caller sets those
 * <em>before</em> calling {@code startDrawingQuads}. Reusing it would wipe the state the current caller
 * just established and break the render that is about to happen.
 *
 * <p>The companion hook on {@code onDrawCall} covers the mirror case: with recovery in place a genuine
 * re-entrant draw no longer dies at {@code startDrawing}, so the outer call can reach its own
 * {@code draw()} after the inner one already reset the state. Letting that be a no-op instead of
 * {@code "NOT CONSTRUCTING VERTICES"} is the difference between one wrong-looking item and another
 * crash loop.
 *
 * <p>This does not fix whatever threw first. It stops one bad render from taking the session with it,
 * and the warning below is there so the real exception can be found earlier in the same log.
 *
 * <p>Client only: {@code TessellatorBakedQuad} exists in InfinityLib's single jar but is never loaded
 * on a dedicated server.
 */
@Pseudo
@Mixin(targets = "com.infinityraider.infinitylib.render.tessellation.TessellatorBakedQuad", remap = false)
public abstract class MixinTessellatorBakedQuad {

    /** {@code TessellatorBakedQuad.DRAW_MODE_NOT_DRAWING}, which is a compile-time constant there. */
    @Unique
    private static final int MTMIXINS$NOT_DRAWING = -1;

    @Unique
    private static boolean mtmixins$warned = false;

    @Shadow
    private int drawMode;

    // Shadowed as List<?>: the declared types are List<BakedQuad> and List<VertexData>, but generics
    // erase and both descriptors are Ljava/util/List;, so this matches without naming InfinityLib's
    // VertexData. Only cleared, never reassigned, so @Final is honest.
    @Shadow
    @Final
    private List<?> quads;

    @Shadow
    @Final
    private List<?> vertexData;

    @Inject(method = "startDrawing", at = @At("HEAD"))
    private void mtmixins$recoverFromLeakedDrawState(int requestedMode, CallbackInfo ci) {
        if (this.drawMode == MTMIXINS$NOT_DRAWING) {
            return;
        }

        this.quads.clear();
        this.vertexData.clear();
        this.drawMode = MTMIXINS$NOT_DRAWING;

        if (!mtmixins$warned) {
            mtmixins$warned = true;
            MedievalTimesMixins.LOG.warn(
                    "InfinityLib's baked-quad tessellator was still marked as drawing, which means an "
                            + "earlier render threw between startDrawingQuads and draw and left it stuck. "
                            + "Resetting it instead of throwing ALREADY CONSTRUCTING VERTICES, which "
                            + "would otherwise repeat for the rest of this session. The render that "
                            + "actually failed is earlier in this log - that is the one worth fixing.");
        }
    }

    @Inject(method = "onDrawCall", at = @At("HEAD"), cancellable = true)
    private void mtmixins$tolerateRedundantDraw(CallbackInfo ci) {
        if (this.drawMode == MTMIXINS$NOT_DRAWING) {
            ci.cancel();
        }
    }

    // ---- B9: quads whose data cannot be unpacked with the format they declare -------------------

    @Unique
    private static boolean mtmixins$reportedBadQuad = false;

    /**
     * Register B9 &mdash; drop quads whose vertex data is too short for the format they claim, which is
     * what actually threw first in the crash this pair of fixes came from.
     *
     * <p>The chain, from the player's log:
     *
     * <pre>
     * ArrayIndexOutOfBoundsException: 28
     *   at LightUtil.unpack(LightUtil.java:173)
     *   at TessellatorAbstractBase.transformQuad(TessellatorAbstractBase.java:633)
     *   at TessellatorBakedQuad.addQuads(TessellatorBakedQuad.java:144)
     *   at RenderItemAuto.renderItem(RenderItemAuto.java:55)
     *   at BakedInfItemSubModel.getQuads(BakedInfItemSubModel.java:80)
     * </pre>
     *
     * <p>{@code transformQuad} unpacks a quad using {@code quad.getFormat()}, and
     * {@code LightUtil.unpack} indexes the data as {@code v * format.getSize() + offset}. That is only
     * safe while the data really is in that format. It was not:
     *
     * <ul>
     *   <li>the quad's {@code int[]} held 28 ints &mdash; four vertices of vanilla's 7-int
     *       {@code DefaultVertexFormats.ITEM};</li>
     *   <li>the player had a shaderpack loaded, and with shaders OptiFine replaces the shared
     *       {@code ITEM} and {@code BLOCK} format objects with extended ones
     *       ({@code SVertexFormat.makeDefVertexFormatItem}, {@code setDefBakedFormat}) whose
     *       {@code vertexSizeBlock} is <b>14</b> ints per vertex, not 7;</li>
     *   <li>so unpacking reached vertex 2 at int index {@code 2 * 14 = 28}, one past the end. The
     *       exception index is exactly 28, which is how this was pinned down.</li>
     * </ul>
     *
     * <p>In other words the quad was packed with the narrow format and is being read with the wide one,
     * because {@code getFormat()} hands back the shared static object that OptiFine swapped underneath
     * it. That is why only the player running shaders hit it.
     *
     * <p>Nothing here can un-break such a quad: the wide format's element offsets do not describe the
     * narrow data, so there is no correct way to read it. Skipping it means that one item renders
     * without those faces instead of taking the render down, which with B8 above is the difference
     * between a blank icon and an unusable client.
     *
     * <p>Filtering the argument rather than guarding {@code transformQuad} keeps the normal path
     * allocation-free and untouched: when every quad checks out, the original list is returned as-is,
     * and the copy is only built once something is actually wrong.
     *
     * <p>The warning deliberately prints the data length, the declared format and its integer size.
     * If this ever fires for something other than the case above, that line identifies the format
     * responsible without needing another round of log archaeology.
     */
    @ModifyVariable(method = "addQuads", at = @At("HEAD"), argsOnly = true, index = 1)
    private List<BakedQuad> mtmixins$dropQuadsThatCannotBeUnpacked(List<BakedQuad> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return incoming;
        }

        List<BakedQuad> kept = null;
        for (int i = 0; i < incoming.size(); i++) {
            BakedQuad quad = incoming.get(i);
            if (mtmixins$canUnpack(quad)) {
                if (kept != null) {
                    kept.add(quad);
                }
            } else {
                if (kept == null) {
                    kept = new ArrayList<>(incoming.subList(0, i));
                }
                mtmixins$reportBadQuad(quad);
            }
        }
        return kept != null ? kept : incoming;
    }

    @Unique
    private static boolean mtmixins$canUnpack(BakedQuad quad) {
        if (quad == null) {
            return false;
        }
        VertexFormat format = quad.getFormat();
        int[] data = quad.getVertexData();
        if (format == null || data == null) {
            return false;
        }
        // LightUtil.unpack walks four vertices of format.getIntegerSize() ints each. Anything shorter
        // than that cannot be read with this format, whatever the format happens to be.
        return data.length >= 4 * format.getIntegerSize();
    }

    @Unique
    private static void mtmixins$reportBadQuad(BakedQuad quad) {
        if (mtmixins$reportedBadQuad) {
            return;
        }
        mtmixins$reportedBadQuad = true;
        int[] data = quad == null ? null : quad.getVertexData();
        VertexFormat format = quad == null ? null : quad.getFormat();
        MedievalTimesMixins.LOG.warn(
                "Skipping a baked quad whose vertex data is too short for the format it declares: "
                        + "{} ints of data, but the format wants {} ints per vertex ({} for four). "
                        + "Format: {}. Sprite: {}. This is normally OptiFine's shader vertex format "
                        + "being swapped in after the quad was packed with the narrow one - the item "
                        + "will render without these faces rather than crashing the client.",
                data == null ? -1 : data.length,
                format == null ? -1 : format.getIntegerSize(),
                format == null ? -1 : 4 * format.getIntegerSize(),
                format,
                (quad == null || quad.getSprite() == null) ? "?" : quad.getSprite().getIconName());
    }
}
