package com.novotimo.mtmixins.mixin.votifier;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * Register B5 &mdash; the "NBTFix". Reproduces the hand-edited Votifier jar, which fixed three
 * genuine upstream bugs. Verified against the v1.4-1.12 source.
 *
 * <p>Votifier is unmaintained and will not be updated, so there is no upstream fix to lose by
 * patching these here.
 *
 * <p>All three are done with MixinExtras injectors plus {@code @Local} sugar rather than
 * {@code @Overwrite}, which keeps Votifier off this project's compile classpath entirely.
 */
@Pseudo
@Mixin(targets = "com.github.upcraftlp.votifier.reward.store.RewardStoreWorldSavedData", remap = false)
public abstract class MixinRewardStoreWorldSavedData {

    /**
     * Bug 1 &mdash; {@code readFromNBT} reads the wrong index in its inner loop:
     *
     * <pre>
     * for (int i = 0; i &lt; list.tagCount(); i++) {
     *     ...
     *     for (int j = 0; j &lt; rewardList.tagCount(); j++) {
     *         NBTTagCompound rewardTag = rewardList.getCompoundTagAt(i);   // &lt;-- i, should be j
     * </pre>
     *
     * So every reward a player had was read as a copy of whichever entry sat at the outer index,
     * and anyone with more than one stored reward got duplicates of the wrong one (or an empty tag,
     * once {@code i} ran past the inner list's length).
     *
     * <p>{@code ordinal = 1} because {@code getCompoundTagAt} is called twice in the method: first
     * on the outer list with {@code i}, then on the reward list. {@code @Local(index = 8)} is
     * {@code j} &mdash; slots run {@code this}=0, {@code nbt}=1, {@code list}=2, {@code i}=3,
     * {@code compound}=4, {@code playerName}=5, {@code rewardList}=6, {@code rewards}=7,
     * {@code j}=8. Addressing it by slot rather than by ordinal is explicit and will fail loudly
     * rather than silently pick the wrong int if the method ever changes.
     */
    @WrapOperation(
            method = {"readFromNBT", "func_76184_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/nbt/NBTTagList;getCompoundTagAt(I)Lnet/minecraft/nbt/NBTTagCompound;",
                    ordinal = 1,
                    remap = true
            )
    )
    private NBTTagCompound mtmixins$readInnerRewardByInnerIndex(
            NBTTagList rewardList, int outerIndex, Operation<NBTTagCompound> original,
            @Local(index = 8) int innerIndex) {
        return original.call(rewardList, innerIndex);
    }

    /**
     * Bug 2 &mdash; malformed entries were stored anyway. The edited jar skipped a reward whose
     * {@code service} string was empty.
     *
     * <p>Placed on the {@code List.add} call rather than where the original guard sat, so the
     * surrounding reads of {@code address} and {@code timestamp} still happen and are simply
     * discarded. Same outcome, and it avoids naming Votifier's {@code StoredReward} type &mdash;
     * {@code List.add} is erased to {@code (Ljava/lang/Object;)Z}, so {@link Object} matches.
     */
    @WrapWithCondition(
            method = {"readFromNBT", "func_76184_a"},
            at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", remap = true)
    )
    private boolean mtmixins$skipRewardsWithNoService(
            List<Object> rewards, Object reward,
            @Local(index = 10) String service) {
        return service != null && !service.isEmpty();
    }

    /**
     * Bug 3 &mdash; off-by-one in {@code storePlayerReward}:
     *
     * <pre>
     * while (rewards.size() &gt; getMaxStoredRewards()) rewards.remove(0);
     * rewards.add(new StoredReward(...));
     * </pre>
     *
     * The loop trims down to exactly {@code max} and then adds one more, so the stored list settles
     * at {@code max + 1}. The edited jar changed the comparison to {@code >=}.
     *
     * <p>Expressed here by lowering the limit the loop compares against, which is equivalent:
     * {@code size > max - 1} is {@code size >= max}. {@code ordinal = 1} because
     * {@code getMaxStoredRewards()} is called twice &mdash; the first is the
     * {@code if (getMaxStoredRewards() == 0) return;} guard at the top, which must not be touched.
     * That guard also means the loop is only reachable when {@code max >= 1}, so this never goes
     * negative.
     */
    @ModifyExpressionValue(
            method = "storePlayerReward",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/upcraftlp/votifier/reward/store/RewardStoreWorldSavedData;getMaxStoredRewards()I",
                    ordinal = 1
            )
    )
    private int mtmixins$trimToOneBelowMax(int max) {
        return max - 1;
    }
}
