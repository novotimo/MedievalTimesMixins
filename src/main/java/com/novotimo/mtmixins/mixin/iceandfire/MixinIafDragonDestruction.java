package com.novotimo.mtmixins.mixin.iceandfire;

import com.github.alexthe666.iceandfire.entity.EntityDragonBase;
import com.novotimo.mtmixins.bridge.DragonBridge;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B21 &mdash; makes Ice and Fire's dragons obey claim protection, and separates "may it break
 * this" from "may it hurt that".
 *
 * <h2>Why a mixin and not an event</h2>
 *
 * <p>Ice and Fire does post a cancellable {@code DragonFireDamageWorldEvent} at the top of all four
 * {@code destroyArea*} methods, and Civilizations subscribes to it as a fallback. But in
 * {@code destroyAreaFire} and {@code destroyAreaIce} the loop that damages living entities sits
 * <i>inside</i> the block the event guards, so cancelling is all-or-nothing: a town is either
 * flattenable or completely immune to dragon breath. The rule wanted here does not fit that shape
 * &mdash; an untamed dragon should be able to roast people in a town it must not dismantle, while a
 * tamed one must not hurt anybody where PvP is off.
 *
 * <p>A Forge {@code LivingAttackEvent} cannot do the job either: {@code IceAndFire.dragonFire} is a
 * single shared static {@code DamageSource} with the type string {@code "dragon_fire"} and no attacker
 * attached, so {@code getTrueSource()} is null and there is no way to tell which dragon hit you, let
 * alone whether it was tamed.
 *
 * <h2>Why these two call sites
 *
 * <p>Both targets were read out of the shipped jar with {@code javap} rather than guessed, which
 * caught a trap worth recording: <b>the damage calls do not have one owner.</b>
 * {@code destroyAreaFire} and {@code destroyAreaIce} invoke
 * {@code EntityLivingBase.func_70097_a}, while {@code destroyAreaFireCharge} and
 * {@code destroyAreaIceCharge} invoke {@code EntityLiving.func_70097_a}, because their loops use a
 * narrower static type. The same split applies to {@code func_70015_d}. A redirect written against
 * {@code EntityLivingBase} alone would have applied to half the methods and silently missed the
 * charges.
 *
 * <p>{@code EntityDragonBase.func_70685_l} ({@code canEntityBeSeen}) has no such problem: one owner,
 * present in all four methods, and it is the <b>last</b> condition of
 *
 * <pre>
 *   if (!DragonUtils.onSameTeam(destroyer, e) &amp;&amp; !destroyer.isEntityEqual(e) &amp;&amp; destroyer.canEntityBeSeen(e))
 * </pre>
 *
 * <p>so returning false from it skips the damage <i>and</i> the ignition that follows, with the dragon
 * as the receiver and the victim as the argument. One redirect, no captured locals, no reliance on
 * which overload the compiler happened to pick.
 *
 * <h2>Names are written in SRG on purpose</h2>
 *
 * <p>{@code remap = false} throughout and every target spelled as the runtime name taken from the
 * bytecode. That skips the refmap entirely, so these injections cannot fail the way a mis-mapped
 * member does &mdash; by binding in dev and silently not applying in production. SRG names are fixed
 * for 1.12.2.
 *
 * <p>This lives in its own config, {@code mixins.mtmixins.dragonpolicy.json}, so it can be switched off
 * from {@code config/mixinbooter.cfg} without rebuilding. That matters more than usual here: this is
 * only testable on the live server.
 *
 * <h2>Why this config fails soft</h2>
 *
 * <p>{@code required: false} and {@code defaultRequire: 0}, which is the opposite of what the other
 * registers in this mod use, and the reason is that <b>there is a working fallback and the other
 * registers do not have one.</b> If these injections do not apply, {@code DragonBridge} never tells
 * Civilizations the precise path is live, so {@code IafGriefListener} keeps cancelling
 * {@code DragonFireDamageWorldEvent} outright: claims stay protected, but dragon breath also stops
 * hurting anyone standing in one. Blunt, and not what was asked for, but <i>not unprotected</i>.
 *
 * <p>The first version of this file used {@code defaultRequire: 1} on the reasoning that silent
 * non-application is worse than a loud failure. That was wrong here. A mismatched handler signature
 * took the whole server down on startup, and the thing the loud failure was supposed to protect
 * against &mdash; towns quietly losing their claim protection &mdash; could not actually happen,
 * because Civilizations covers it. Mixin still logs a warning when an injection is dropped, and
 * Civilizations logs once when the fallback engages, so neither mode is silent.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.iceandfire.entity.IafDragonDestructionManager", remap = false)
public abstract class MixinIafDragonDestruction {

    /**
     * Every block a dragon's breath or charge would change, one at a time.
     *
     * <p>Refusing here is better than estimating a blast radius: the claim check and the raid journal
     * both get the exact block, so nothing inside a claim is missed and nothing outside one is
     * journalled needlessly.
     *
     * <p>{@code destroyer} is the third parameter of all four target methods, which are static, so it
     * arrives as the last appended argument.
     *
     * <p>It must be declared as {@code EntityDragonBase} and not a supertype. Mixin is lenient about
     * a redirect's <i>receiver</i> but checks appended target parameters for an exact type match, and
     * declaring {@code Entity} here crashed the server on startup with "Found unexpected argument type
     * net.minecraft.entity.Entity at index 5".
     */
    @Redirect(
            method = {
                    "destroyAreaFire",
                    "destroyAreaIce",
                    "destroyAreaFireCharge",
                    "destroyAreaIceCharge",
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/World;func_175656_a"
                            + "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/state/IBlockState;)Z"
            )
    )
    private static boolean mtmixins$gateDragonBlockChange(World world, BlockPos pos, IBlockState state,
                                                          World targetWorld, BlockPos center,
                                                          EntityDragonBase destroyer) {
        if (!DragonBridge.blockChangeAllowed(world, pos, destroyer)) {
            // false is what setBlockState returns when nothing changed, which is exactly the truth.
            return false;
        }
        return world.setBlockState(pos, state);
    }

    /**
     * Every living thing a dragon's breath or charge would burn.
     *
     * <p>Gating the line-of-sight test rather than the damage call skips both the damage and the
     * {@code setFire} on the next line, and dodges the two-owners problem described on the class.
     * The sight test still runs first, so a dragon cannot hit through a wall just because the policy
     * would have allowed it.
     */
    @Redirect(
            method = {
                    "destroyAreaFire",
                    "destroyAreaIce",
                    "destroyAreaFireCharge",
                    "destroyAreaIceCharge",
            },
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/alexthe666/iceandfire/entity/EntityDragonBase;func_70685_l"
                            + "(Lnet/minecraft/entity/Entity;)Z"
            )
    )
    private static boolean mtmixins$gateDragonBreathVictim(EntityDragonBase dragon, Entity victim) {
        return dragon.canEntityBeSeen(victim) && DragonBridge.damageAllowed(dragon, victim);
    }
}
