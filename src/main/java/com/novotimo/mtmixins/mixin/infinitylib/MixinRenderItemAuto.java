package com.novotimo.mtmixins.mixin.infinitylib;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Register B9 &mdash; re-bake InfinityLib's cached item quads when the vertex format changes, instead of
 * serving quads that no longer match it.
 *
 * <h2>Why the cache goes stale</h2>
 *
 * <p>{@code RenderItemAuto.renderItem} is, in full:
 *
 * <pre>
 * String modelId = item.getModelId(stack);
 * List&lt;BakedQuad&gt; quads = this.models.get(modelId);
 * if (quads == null) {
 *     quads = ItemQuadGenerator.generateItemQuads(DefaultVertexFormats.ITEM, ...);
 *     this.models.put(modelId, quads);
 * }
 * tessellator.addQuads(quads);
 * </pre>
 *
 * <p>The quads are packed against {@code DefaultVertexFormats.ITEM} as it stands at bake time, and cached
 * for ever under a key that is only the model id. {@code models} is a {@code private final Map} with no
 * {@code clear}, and nothing in {@code infinitylib/render/} implements
 * {@code IResourceManagerReloadListener}, so once an entry is in there it is never rebuilt.
 *
 * <p>That is fine until the format changes underneath it, and with a shaderpack loaded it does. OptiFine
 * builds an extended format for shaders &mdash; {@code SVertexFormat.makeDefVertexFormatItem}, with
 * {@code vertexSizeBlock = 14} ints per vertex against vanilla's 7 &mdash; and applies it by copying the
 * elements into the existing shared format object ({@code SVertexFormat.copy}, {@code setDefBakedFormat})
 * rather than swapping the static field, so every quad already holding that reference silently starts
 * reporting the wider size while its own {@code int[]} stays the narrow length it was packed at.
 *
 * <p>The result, from the crash this came from: {@code TessellatorAbstractBase.transformQuad} asks the
 * quad for its format, {@code LightUtil.unpack} indexes the data as
 * {@code vertex * format.getSize() + offset}, and on a 28-int array with a 14-int-per-vertex format it
 * reaches int index {@code 2 * 14 = 28} &mdash; one past the end.
 * {@code ArrayIndexOutOfBoundsException: 28}, exactly as logged. Because
 * {@code BakedInfItemSubModel.getQuads} has no try/finally, that throw also strands the shared
 * tessellator; see {@link MixinTessellatorBakedQuad} for that half.
 *
 * <h2>The fix</h2>
 *
 * <p>Put the format's width into the cache key. A format change then misses the cache, the quads are
 * regenerated against the format that is actually current, and the item renders <em>properly</em> &mdash;
 * which is the part a guard further downstream cannot give you, since nothing can read narrow data with
 * a wide format's offsets.
 *
 * <p>Cost is one cache entry per item model per distinct format width, so at most double. Everything the
 * key already distinguished it still distinguishes.
 *
 * <p>This is only sound because the bake reads {@code DefaultVertexFormats.ITEM} fresh on every miss, so
 * keying on that same object's width cannot disagree with what the bake will use.
 *
 * <p>Client only; InfinityLib's 1.12.2 line is long abandoned upstream.
 */
@Pseudo
@Mixin(targets = "com.infinityraider.infinitylib.render.item.RenderItemAuto", remap = false)
public abstract class MixinRenderItemAuto {

    /**
     * {@code remap = true} on the {@code @At} because {@code getModelId} is InfinityLib's own but the
     * handler body below touches vanilla; the selector itself needs no dual name, as
     * {@code renderItem} comes from InfinityLib's {@code IItemRenderingHandler}, not from Minecraft.
     */
    @ModifyExpressionValue(
            method = "renderItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/infinityraider/infinitylib/item/IAutoRenderedItem;"
                            + "getModelId(Lnet/minecraft/item/ItemStack;)Ljava/lang/String;"
            )
    )
    private String mtmixins$keyQuadCacheByVertexFormat(String modelId) {
        if (modelId == null) {
            return null;
        }
        // getIntegerSize() is 7 for vanilla ITEM and 14 for OptiFine's shader format, so the two never
        // share an entry. Read from the same static the bake reads, on every call, so the key always
        // describes the format the bake would use right now.
        return modelId + '#' + DefaultVertexFormats.ITEM.getIntegerSize();
    }
}
