package com.novotimo.mtmixins.mixin.vanilla;

import com.novotimo.mtmixins.util.NanGuard;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B25 &mdash; a knockback with no horizontal direction turns the victim's motion into NaN.
 * On a player that is permanent: the player is kicked, saved at NaN, and kicked again on every login.
 *
 * <h2>The bug</h2>
 *
 * <p>Vanilla {@code EntityLivingBase.knockBack(attacker, strength, xRatio, zRatio)} normalises the
 * direction by dividing by {@code f = sqrt(xRatio^2 + zRatio^2)}:
 *
 * <pre>
 *   this.motionX -= xRatio / (double) f * (double) strength;
 *   this.motionZ -= zRatio / (double) f * (double) strength;
 * </pre>
 *
 * <p>With both ratios zero that is {@code 0 / 0}: motionX and motionZ become NaN while motionY, set
 * separately, stays finite. Vanilla's own caller in {@code attackEntityFrom} never passes a zero
 * vector &mdash; it re-rolls a tiny random direction while the distance is under 0.01 &mdash; but
 * mods call {@code knockBack} directly with whatever direction they computed, and an attacker standing
 * at the victim's exact X and Z (a rider, a mount, a spell centred on its target) gives zero. NaN
 * ratios from an attacker that is itself at NaN do the same.
 *
 * <p>For a player, knockback also sets {@code velocityChanged}, so the entity tracker sends the NaN
 * velocity to the player's own client. The client moves to NaN, reports it, and
 * {@code NetHandlerPlayServer} kicks with "Invalid move player packet received". The player is saved
 * at NaN, and vanilla's load-time sanity check ({@code Math.abs(motion) > 10}) is false for NaN, so it
 * comes back on every login and the client is kicked again within a second.
 *
 * <h2>Evidence</h2>
 *
 * <p>Prod, 13 October 2026: ripmaxwell2013 was kicked at 19:34:18 with "Invalid move player packet
 * received", then logged in 27 times over the next 45 minutes at {@code (NaN, 30.93117885469533, NaN)},
 * kicked each time, across two server restarts. NaN X and Z with a finite Y is exactly what this
 * division produces. Agent_Of_KGB was kicked at 19:34:56 and logged in eight times at the same NaN X
 * and Z and the <i>identical</i> Y, which is what teleporting to a player already at NaN looks like. At 19:50:06 ripmaxwell2013
 * "fell out of the world" during one of those logins. ForgeEssentials stored the NaN death location,
 * and its logout save threw on the NaN and took the server down (crash-2026-10-13_19.50.07; see
 * Register B26).
 *
 * <h2>The fix</h2>
 *
 * <p>After {@code knockBack} returns, any motion component that is not finite is set to zero and
 * the finite ones are kept. A zero-direction knockback therefore pushes nobody sideways, which is the
 * only sensible meaning it has. Injected at every {@code RETURN} rather than by guarding the division,
 * so it also covers ratios that a {@code LivingKnockBackEvent} listener rewrote, and motion that was
 * already NaN on the way in.
 *
 * <p>Each occurrence is logged with the first stack frame outside {@code knockBack}, so the mod making
 * the call can be identified. Rate limited (see {@link NanGuard#reportKnockback}).
 *
 * <p>Common, not server only: an integrated server has the same arithmetic, and on a client the check
 * is a few comparisons per knockback. Its own config file, {@code mixins.mtmixins.nanguard.json},
 * shared with {@link MixinEntityPlayerMPNaNGuard}.
 */
@Mixin(EntityLivingBase.class)
public abstract class MixinEntityLivingBaseKnockBackNaN {

    @Inject(method = "knockBack", at = @At("RETURN"))
    private void mtmixins$dropNonFiniteKnockback(Entity attacker, float strength, double xRatio, double zRatio,
                                                 CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (NanGuard.motionIsFinite(self)) {
            return;
        }

        NanGuard.clearNonFiniteMotion(self);
        if (!self.world.isRemote) {
            NanGuard.reportKnockback(self, attacker, xRatio, zRatio);
        }
    }
}
