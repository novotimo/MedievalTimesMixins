package com.novotimo.mtmixins.mixin.horsepower;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Register B35 &mdash; JEI 4.16.5 crashes the client at startup ("Initializing game") on Horse Power's
 * fluid press recipes.
 *
 * <h2>The bug</h2>
 *
 * <p>JEI commit {@code 6dbc1ad009} (11 July 2026, a backport of the 2022 fix for JEI issue #2790,
 * "recipes listed twice") removes duplicates from every list {@code RecipeRegistry.getRecipeWrappers}
 * returns by passing it through a {@code LinkedHashSet}. The modern original does the same with
 * {@code Stream.distinct()}. So from 4.16.5 on, a recipe wrapper's {@code equals} and {@code hashCode}
 * decide what JEI shows. Before that, JEI 1.12 kept wrappers in identity-based collections (its
 * hidden-recipe set and its recipe-to-wrapper map), so those two methods were almost never called.
 *
 * <p>Of the 189 mods in the pack, only Horse Power's four JEI wrappers define their own {@code equals}
 * and {@code hashCode}. This one is broken in two ways:
 *
 * <ul>
 *   <li>{@code hashCode()} calls {@code output.hashCode()} with no check. {@code PressRecipeMaker} builds
 *       every fluid recipe's wrapper with a {@code null} item output (Horse Power's other constructor,
 *       from a {@code PressRecipe}, passes {@code ItemStack.EMPTY}). Horse Power commit {@code 1c31fa6091}
 *       ("Added fluid support to the press", July 2018) made {@code equals} allow a null output and missed
 *       {@code hashCode}. The default recipe {@code ore:treeLeaves@8-fluid:water@1000} is therefore enough
 *       to crash JEI's startup.</li>
 *   <li>{@code equals} is too loose to be relied on now. Its input loop only rejects a match when the other
 *       recipe's input is a wildcard ({@code meta 32767}) that does not match, so two recipes with different
 *       non-wildcard inputs and the same fluid count as equal ({@code FluidStack.equals} also ignores the
 *       amount). The hash is built from {@code ItemStack}'s identity hash, so it does not agree with
 *       {@code equals} either.</li>
 * </ul>
 *
 * <h2>Evidence</h2>
 *
 * <p>Tim's client, 16 October 2026, test instance with JEI 4.16.5.1030:
 *
 * <pre>
 *   net.minecraftforge.fml.common.LoaderExceptionModCrash: Caught exception from Just Enough Items (jei)
 *   Caused by: java.lang.NullPointerException
 *     at se.gory_moon.horsepower.jei.press.PressRecipeWrapper.hashCode(PressRecipeWrapper.java:99)
 *     at java.util.HashMap.hash(HashMap.java:340)
 *     ...
 *     at java.util.LinkedHashSet.&lt;init&gt;(LinkedHashSet.java:169)
 *     at mezz.jei.recipes.RecipeRegistry.getRecipeWrappers(RecipeRegistry.java:638)
 *     at mezz.jei.recipes.RecipeRegistry.isCategoryVisible(RecipeRegistry.java:387)
 * </pre>
 *
 * <h2>The fix</h2>
 *
 * <p>Replaces both methods with the equality Horse Power meant: two press wrappers are the same recipe
 * when they press the same inputs into the same output. That keeps JEI's change doing what it is for (a
 * recipe reached twice is listed once) without merging two recipes that are different.
 *
 * <ul>
 *   <li><b>Inputs</b> match when every stack on each side has a counterpart on the other with the same item,
 *       count and NBT, and the same metadata unless either one is a wildcard. This is the comparison Horse
 *       Power's loop was reaching for: a wildcard input such as {@code minecraft:leaves:*} (from the
 *       {@code PressRecipe} constructor) matches the list of subtypes JEI expands it into (from
 *       {@code PressRecipeMaker}).</li>
 *   <li><b>Item output</b>: {@code ItemStack.areItemStacksEqual}, with {@code null} read as
 *       {@code ItemStack.EMPTY}.</li>
 *   <li><b>Fluid output</b>: {@code FluidStack.isFluidStackIdentical}, which includes the amount, so
 *       {@code water@1000} and {@code water@500} stay two recipes.</li>
 * </ul>
 *
 * <p>The hash uses only what equal wrappers must share: the set of input (item, count) pairs (metadata
 * excluded because of the wildcard rule), and the output's item, metadata and count, or its fluid and
 * amount.
 *
 * <p>Left alone, deliberately:
 *
 * <ul>
 *   <li>JEI's change. It fixes a real bug, and the only mod in the pack it breaks is this one.</li>
 *   <li>The {@code null} in {@code PressRecipeMaker}. Only {@code equals}, {@code hashCode} and the
 *       item-output branch of {@code getIngredients} (never taken for fluid recipes) read the field, so
 *       once these two methods accept {@code null}, nothing else needs to change.</li>
 *   <li>Horse Power's chopping, manual chopping and grindstone wrappers. Their makers always pass real
 *       stacks, so their hashes cannot throw. Their hashes are identity-based (chopping) or content-based
 *       with an identity {@code equals} on the output (grindstone), so JEI's dedup only ever removes a
 *       wrapper listed twice. That is what it is for.</li>
 * </ul>
 *
 * <p>Client only: JEI is not on the server, which never loads this class. {@code @Pseudo} and a string
 * target, so Horse Power is not on the compile classpath. Its own config file,
 * {@code mixins.mtmixins.horsepowerjei.json}, so it can be switched off in {@code config/mixinbooter.cfg}
 * without a rebuild.
 */
@Pseudo
@Mixin(targets = "se.gory_moon.horsepower.jei.press.PressRecipeWrapper", remap = false)
public abstract class MixinPressRecipeWrapperEquality {

    @Shadow @Final private List<List<ItemStack>> inputs;
    @Shadow @Final private ItemStack output;
    @Shadow @Final private FluidStack fluidOutput;

    @Inject(method = "equals(Ljava/lang/Object;)Z", at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$sameRecipe(Object o, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == o) {
            cir.setReturnValue(true);
            return;
        }
        if (o == null || o.getClass() != this.getClass()) {
            cir.setReturnValue(false);
            return;
        }
        MixinPressRecipeWrapperEquality that = (MixinPressRecipeWrapperEquality) o;
        cir.setReturnValue(mtmixins$sameFluid(this.fluidOutput, that.fluidOutput)
                && ItemStack.areItemStacksEqual(mtmixins$orEmpty(this.output), mtmixins$orEmpty(that.output))
                && mtmixins$covers(this.inputs, that.inputs)
                && mtmixins$covers(that.inputs, this.inputs));
    }

    @Inject(method = "hashCode()I", at = @At("HEAD"), cancellable = true, remap = false)
    private void mtmixins$recipeHash(CallbackInfoReturnable<Integer> cir) {
        // A set, so neither the order of the subtypes nor a wildcard's expansion changes the result.
        Set<Integer> inputKeys = new HashSet<>();
        for (List<ItemStack> slot : this.inputs) {
            for (ItemStack stack : slot) {
                if (stack != null && !stack.isEmpty()) {
                    inputKeys.add(31 * stack.getItem().hashCode() + stack.getCount());
                }
            }
        }
        int result = inputKeys.hashCode();

        ItemStack out = mtmixins$orEmpty(this.output);
        result = 31 * result + (out.isEmpty() ? 0 : Objects.hash(out.getItem(), out.getMetadata(), out.getCount()));

        FluidStack fluid = this.fluidOutput;
        result = 31 * result + (fluid == null ? 0 : Objects.hash(fluid.getFluid(), fluid.amount));
        cir.setReturnValue(result);
    }

    @Unique
    private static ItemStack mtmixins$orEmpty(ItemStack stack) {
        return stack == null ? ItemStack.EMPTY : stack;
    }

    @Unique
    private static boolean mtmixins$sameFluid(FluidStack a, FluidStack b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.isFluidStackIdentical(b);
    }

    /** Every non-empty stack in each slot of {@code a} has a matching stack in the same slot of {@code b}. */
    @Unique
    private static boolean mtmixins$covers(List<List<ItemStack>> a, List<List<ItemStack>> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int slot = 0; slot < a.size(); slot++) {
            for (ItemStack stack : a.get(slot)) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                boolean found = false;
                for (ItemStack candidate : b.get(slot)) {
                    if (candidate != null && !candidate.isEmpty() && mtmixins$sameInput(stack, candidate)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
        }
        return true;
    }

    @Unique
    private static boolean mtmixins$sameInput(ItemStack x, ItemStack y) {
        return x.getItem() == y.getItem()
                && (x.getMetadata() == y.getMetadata()
                    || x.getMetadata() == OreDictionary.WILDCARD_VALUE
                    || y.getMetadata() == OreDictionary.WILDCARD_VALUE)
                && x.getCount() == y.getCount()
                && ItemStack.areItemStackTagsEqual(x, y);
    }
}
