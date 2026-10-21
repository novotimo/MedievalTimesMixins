package com.novotimo.mtmixins.mixin.ancientwarfare;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Set;

import net.shadowmage.ancientwarfare.npc.gamedata.TeamData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B39 &mdash; Ancient Warfare no longer throws a player off at login with "Internal server error"
 * when they have left two or more teams.
 *
 * <h2>The fault</h2>
 *
 * <pre>
 * [FML]: Exception caught during firing event ...PlayerEvent$PlayerLoggedInEvent
 * java.util.ConcurrentModificationException
 *     at ...TeamData.checkAndUpdatePlayerTeamMemberships(TeamData.java:147)
 *     at ...FactionTracker.onPlayerLogin(FactionTracker.java:27)
 * [NetworkSystem]: Failed to handle packet  -&gt;  "lost connection: Internal server error"
 * </pre>
 *
 * <p>Ancient Warfare 2.9.20's {@code TeamData.checkAndUpdatePlayerTeamMemberships} walks the player's set of
 * teams ({@code playerTeamsLookup.get(playerName)}) and, for each team the player is no longer in, calls
 * {@code removeTeamMember}, which removes that team from the very same set. The next step of the loop then
 * throws. It needs two or more teams to drop out at once, which is why it is rare: prod's log had three, on
 * 6, 9 and 10 October 2026, each player getting in on the next try. AWFactionFix 1.0.3-test was meant to fix
 * this and fails to patch on every start.
 *
 * <h2>The fix</h2>
 *
 * <p>That loop iterates a copy of the set. Every team is still checked, every removal still happens, and
 * {@code removeTeamMember} still updates the real set; only the iteration no longer trips over it. The
 * first {@code Set.iterator()} in the method walks the player's current team names, which nothing changes
 * mid-loop, so only the second ({@code ordinal = 1}) is redirected. Both checked with {@code javap}.
 *
 * <p>{@code remap = false}: Ancient Warfare is not obfuscated and the target is a JDK method. Server only.
 * Lives in {@code mixins.mtmixins.awteams.json}, so it can be switched off in {@code config/mixinbooter.cfg}
 * without a rebuild. With it in place AWFactionFix has nothing left to do and can come out of prod's mods.
 */
@Mixin(value = TeamData.class, remap = false)
public abstract class MixinTeamDataMembership {

    @Redirect(method = "checkAndUpdatePlayerTeamMemberships",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;", ordinal = 1),
            remap = false)
    private Iterator<?> mtmixins$iterateCopy(Set<?> teams) {
        return new ArrayList<Object>(teams).iterator();
    }
}
