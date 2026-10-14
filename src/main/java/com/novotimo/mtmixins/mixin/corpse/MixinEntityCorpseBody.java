package com.novotimo.mtmixins.mixin.corpse;

import com.novotimo.mtmixins.util.CorpseBodies;
import de.maxhenkel.corpse.entities.EntityCorpse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B31 &mdash; corpses go missing: "bodies are going under bedrock", and bodies that leave the
 * place their player died.
 *
 * <h2>The bugs</h2>
 *
 * <p>Corpse 1.12.2-1.0.8, {@code EntityCorpse}:
 *
 * <pre>
 *   createFromDeath:  setPosition(x, posY &lt; 0 ? 0 : posY, z)
 *   onUpdate:         if (posY &lt; 0) setPositionAndUpdate(posX, 0, posZ)
 *                     move(SELF, motionX, motionY, motionZ)     // only motionY is ever managed
 * </pre>
 *
 * <ul>
 *   <li>A body below y = 0 is put at y = 0, which is the bottom bedrock layer in the overworld and the
 *       Nether, and open void in the End. Out of reach without creative either way.</li>
 *   <li>Sideways motion is applied every tick and never decays; only hitting something stops it. Any
 *       explosion nearby or any flowing water starts it, and the body slides in a straight line until
 *       it meets a wall.</li>
 *   <li>A body lying in a nether or end portal travels through it, like any other non-boss entity.</li>
 * </ul>
 *
 * <h2>The fix</h2>
 *
 * <ul>
 *   <li>Any body below y = 1, which takes in those already embedded in bedrock on the live world, is
 *       moved onto the nearest floor in its own column or within 8 blocks: the highest one, or the
 *       lowest under a ceiling (the Nether). If there is none, onto the floor at the dimension's spawn,
 *       which in the End is the obsidian platform. Logged with both positions, for answering tickets.
 *       New bodies are moved before they are spawned, by {@link MixinDeathEventsCorpse}.</li>
 *   <li>Sideways motion decays as it does for a dropped item, and explosions and flowing water no longer
 *       push the body at all.</li>
 *   <li>The body counts as a boss for portals, which is the only thing vanilla asks that for, so it
 *       stays in the portal it is lying in.</li>
 * </ul>
 *
 * <p>The three overrides are written in SRG with no {@code @Override}: in the development environment
 * they are plain new methods, and in production they override {@code Entity}'s.
 *
 * <p>Server only: the server sends a corpse's position and velocity every tick (tracking frequency 1,
 * velocity updates on), so clients follow without the mixin. Its own config file,
 * {@code mixins.mtmixins.corpsebody.json}, so it can be switched off in {@code config/mixinbooter.cfg}
 * without a rebuild.
 */
@Mixin(value = EntityCorpse.class, remap = false)
public abstract class MixinEntityCorpseBody {

    /** Ticks until the next try, after a rescue that found nowhere to go. */
    @Unique
    private int mtmixins$rescueCooldown;

    @Inject(method = "func_70071_h_", at = @At("HEAD"), remap = false)
    private void mtmixins$rescueFromBelowFloor(CallbackInfo ci) {
        EntityCorpse self = (EntityCorpse) (Object) this;
        if (self.world.isRemote || !CorpseBodies.needsRescue(self)) {
            return;
        }
        if (mtmixins$rescueCooldown > 0) {
            mtmixins$rescueCooldown--;
            return;
        }
        if (!CorpseBodies.rescue(self, "in the world")) {
            mtmixins$rescueCooldown = 1200;
        }
    }

    @Inject(method = "func_70071_h_",
            at = @At(value = "INVOKE",
                    target = "Lde/maxhenkel/corpse/entities/EntityCorpse;func_70091_d(Lnet/minecraft/entity/MoverType;DDD)V",
                    shift = At.Shift.AFTER),
            remap = false)
    private void mtmixins$friction(CallbackInfo ci) {
        EntityCorpse self = (EntityCorpse) (Object) this;
        if (!self.world.isRemote) {
            CorpseBodies.applyFriction(self);
        }
    }

    /** {@code Entity.isNonBoss}: false keeps the body out of nether and end portals. */
    public boolean func_184222_aU() {
        return false;
    }

    /** {@code Entity.isPushedByWater}. */
    public boolean func_96092_aw() {
        return false;
    }

    /** {@code Entity.isImmuneToExplosions}: no knockback. A corpse takes no damage anyway. */
    public boolean func_180427_aV() {
        return true;
    }
}
