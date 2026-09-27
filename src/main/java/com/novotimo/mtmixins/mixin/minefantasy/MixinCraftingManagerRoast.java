package com.novotimo.mtmixins.mixin.minefantasy;

import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Register A14 &mdash; stops MineFantasy Reforged desyncing the roast recipe registry at runtime.
 * This has caused at least three "Fatally missing registry entries" outages.
 *
 * <p>{@code CraftingManagerRoast.findMatchingRecipe} falls back to inventing a roast recipe when
 * nothing matches:
 *
 * <pre>
 * if (ConfigCrafting.canCookBasics) {
 *     ItemStack smelted = FurnaceRecipes.instance().getSmeltingResult(input);
 *     if (!smelted.isEmpty() &amp;&amp; smelted.getItem() instanceof ItemFood) {
 *         RoastRecipe recipe = new RoastRecipe(smelted, ...);
 *         addToRegistry(recipe, smelted);   // &lt;-- unfreeze(), register(), freeze()
 *         return recipe;
 *     }
 * }
 * </pre>
 *
 * <p>{@code addToRegistry} unfreezes the {@code minefantasyreforged:roast_recipes} Forge registry,
 * registers a new entry named {@code minefantasyreforged:<output item path>}, and refreezes it
 * &mdash; at arbitrary runtime, long after registration should have finished. The server grows past
 * MineFantasy's 30 shipped recipes the moment a player puts raw mutton, a potato or bread on a
 * firepit, and every client that connects afterwards fails the registry handshake because it only
 * ever has 30.
 *
 * <p>The per-recipe config gate cannot prevent this in general: the key is built with the namespace
 * forced to {@code minefantasyreforged}, so blocking one food does nothing for the next, and a pack
 * with Pam's HarvestCraft has dozens waiting.
 *
 * <p><b>The fix loses no gameplay.</b> Read the original's ordering: {@code addToRegistry} is called
 * and then the recipe is returned <i>regardless of whether registration happened</i>. Suppressing
 * the registration still hands the firepit a working recipe, so non-MineFantasy food still cooks.
 * It simply stops polluting a registry that has to match the client's.
 *
 * <p><b>Known cost.</b> Because the recipe is never cached in the registry, subsequent lookups no
 * longer short-circuit, so a firepit cooking non-MineFantasy food rebuilds a small recipe object on
 * each check instead of finding one. That is a handful of short-lived allocations per active firepit
 * per tick, against a tick budget where loose items alone cost 27 ms/s, so it is not worth
 * complicating this to avoid. If it ever shows up in a profile, cache by output item here instead.
 */
@Pseudo
@Mixin(targets = "minefantasy.mfr.registry.recipe.CraftingManagerRoast", remap = false)
public abstract class MixinCraftingManagerRoast {

    /** Outputs already reported, so the log records the cause once per food rather than per tick. */
    @Unique
    private static final Set<Item> mtmixins$reported =
            Collections.newSetFromMap(new WeakHashMap<Item, Boolean>());

    /**
     * {@code @Redirect} rather than {@code @WrapOperation} because the original must never run;
     * redirecting a call away is exactly what it is for, and it sidesteps expressing a void
     * {@code Operation}.
     *
     * <p>{@code addToRegistry} is private static and its first parameter is MineFantasy's
     * {@code RoastRecipeBase}, which is not on this mod's compile classpath. {@code @Coerce} lets
     * the handler widen it to {@link Object} &mdash; the body does not need the real type.
     *
     * <p>The invoke owner is {@code CraftingManagerRoast} itself, confirmed from the constant pool
     * rather than assumed.
     */
    @Redirect(
            method = "findMatchingRecipe",
            at = @At(
                    value = "INVOKE",
                    target = "Lminefantasy/mfr/registry/recipe/CraftingManagerRoast;"
                            + "addToRegistry(Lminefantasy/mfr/registry/recipe/RoastRecipeBase;"
                            + "Lnet/minecraft/item/ItemStack;)V"
            )
    )
    private static void mtmixins$suppressRuntimeRoastRegistration(@Coerce Object recipe, ItemStack output) {
        if (!output.isEmpty() && mtmixins$reported.add(output.getItem())) {
            MedievalTimesMixins.LOG.info(
                    "Suppressed MineFantasy runtime roast registration for {} (it still cooks; "
                            + "registering it would desync the roast registry with clients).",
                    output.getItem().getRegistryName());
        }
    }
}
