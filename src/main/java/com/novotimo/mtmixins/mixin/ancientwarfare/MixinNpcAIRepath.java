package com.novotimo.mtmixins.mixin.ancientwarfare;

import net.minecraft.pathfinding.Path;
import net.minecraft.pathfinding.PathNavigate;
import net.minecraft.pathfinding.PathPoint;
import net.minecraft.util.math.BlockPos;
import net.shadowmage.ancientwarfare.npc.ai.NpcAI;
import net.shadowmage.ancientwarfare.npc.entity.NpcBase;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Register B40 &mdash; Ancient Warfare NPCs no longer compute a new path every tick while walking somewhere.
 *
 * <h2>The fault</h2>
 *
 * <p>Every NPC movement task (patrol, upkeep, work, courier, trader, attack, follow, stay-at-home, ...) moves
 * through {@code NpcAI.moveToPosition(double, double, double, double)}, called once per tick from the task's
 * {@code updateTask}. Its retry delay is decremented by 10 on each call but only ever set to 5, 10 or 20 (30
 * for long distances), so it is back at or below zero on the next call or the one after:
 *
 * <pre>
 *   moveRetryDelay -= 10;
 *   if (moveRetryDelay &lt;= 0) {
 *       if (sqDist &gt; maxPFDistSq) { moveLongDistance(x, y, z); moveRetryDelay = 30; }
 *       else { setPath(x, y, z); moveRetryDelay = 5; (+5 past 16 blocks, +10 more past 32) }
 *   }
 * </pre>
 *
 * <p>An NPC within 32 blocks of its target therefore runs a full A* search (up to 200 nodes, each checking
 * collision boxes through the world, which also fires MalisisCore's and SecretRooms' collision hooks) every
 * tick, and every other tick further out. An unreachable target &mdash; a patrol point it cannot get within 2
 * blocks of, an upkeep chest in a wall or on another floor &mdash; always costs the full 200 nodes and never
 * stops. Prod's spark profile of 2026-10-22 (7 players, 58 minutes): 25% of the server thread in
 * {@code NpcAIPlayerOwnedPatrol} (14.9%) and {@code NpcAIPlayerOwnedGetFood} (10.0%) path searches alone, 30%
 * in pathfinding overall. Upstream AncientWarfare2 (1.12.x, last changed 2021) has the same code.
 *
 * <h2>The fix</h2>
 *
 * <ol>
 *   <li><b>The delay counts ticks.</b> The decrement is 1 instead of 10, so a new path is tried every 5, 10
 *       or 20 ticks by distance and every 30 for long hauls. Every caller only ever resets the delay to 0 to
 *       force an immediate path, which still works.</li>
 *   <li><b>A path to the same block is kept.</b> While the target is the same block as last time and the
 *       navigator still holds the path this task set and has not finished it, no new search is made.
 *       Vanilla's own stuck check clears a path the NPC cannot follow, which lets the next attempt through.
 *       Newer vanilla (1.14+) does the same in {@code PathNavigator}.</li>
 *   <li><b>A search that gets nowhere backs off.</b> If the new path is null, already finished, or ends no
 *       closer to the target than the NPC already is, the next attempt for that same block waits 2 seconds,
 *       and 5 seconds after three in a row. A different target block clears the back-off at once. Paper's
 *       "Optimize Pathfinding" patch does the same for vanilla mobs.</li>
 * </ol>
 *
 * <p>In game: NPCs walk the same routes. One that cannot reach its patrol point, upkeep chest or home stands
 * still exactly as before, but searches every few seconds instead of twenty times a second. A combat NPC
 * chasing a moving target follows a path at most 5&ndash;20 ticks old, about what vanilla zombies use.
 * {@code moveLongDistance} (targets beyond follow range) only gets the tick-based delay.
 *
 * <p>{@code remap = false}: Ancient Warfare is not obfuscated and {@code NpcAI}'s own members keep their names;
 * the {@code bipush 10} (ordinal 0, the decrement; ordinal 1 is the {@code += 10} past 32 blocks) and the
 * single {@code setPath(DDD)V} in {@code moveToPosition(DDDD)V} were checked with {@code javap} against
 * ancientwarfare-1.12.2-2.9.20.jar. AI tasks only run on the server. Lives in
 * {@code mixins.mtmixins.awpathing.json}, so it can be switched off in {@code config/mixinbooter.cfg}
 * without a rebuild.
 */
@Mixin(value = NpcAI.class, remap = false)
public abstract class MixinNpcAIRepath {

    /** Ticks to wait after one or two searches that got nowhere, and after three or more in a row. */
    @Unique private static final int mtmixins$BACKOFF_SHORT = 40;
    @Unique private static final int mtmixins$BACKOFF_LONG = 100;
    @Unique private static final int mtmixins$FAILS_FOR_LONG = 3;

    @Shadow protected NpcBase npc;

    @Shadow protected abstract void setPath(double x, double y, double z);

    /** The block this task last searched a path to, as {@link BlockPos#toLong()}. */
    @Unique private long mtmixins$target = Long.MIN_VALUE;
    /** The path this task last handed the navigator, to tell it apart from another task's. */
    @Unique private Path mtmixins$path;
    @Unique private int mtmixins$fails;
    @Unique private long mtmixins$retryAt;

    @ModifyConstant(method = "moveToPosition(DDDD)V", constant = @Constant(intValue = 10, ordinal = 0), remap = false)
    private int mtmixins$delayCountsTicks(int ten) {
        return 1;
    }

    @Redirect(method = "moveToPosition(DDDD)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/shadowmage/ancientwarfare/npc/ai/NpcAI;setPath(DDD)V"),
            remap = false)
    private void mtmixins$setPathUnlessPointless(NpcAI<?> self, double x, double y, double z) {
        long target = new BlockPos(x, y, z).toLong();
        long now = npc.world.getTotalWorldTime();
        PathNavigate navigator = npc.getNavigator();

        if (target != mtmixins$target) {
            mtmixins$target = target;
            mtmixins$fails = 0;
            mtmixins$retryAt = 0L;
        } else {
            Path current = navigator.getPath();
            if (current != null && current == mtmixins$path && !current.isFinished()) {
                return;
            }
            if (now < mtmixins$retryAt) {
                return;
            }
        }

        double before = npc.getDistanceSq(x, y, z);
        setPath(x, y, z);
        Path path = navigator.getPath();
        mtmixins$path = path;

        if (mtmixins$gotNowhere(path, x, y, z, before)) {
            mtmixins$fails++;
            mtmixins$retryAt = now + (mtmixins$fails >= mtmixins$FAILS_FOR_LONG ? mtmixins$BACKOFF_LONG : mtmixins$BACKOFF_SHORT);
        } else {
            mtmixins$fails = 0;
            mtmixins$retryAt = 0L;
        }
    }

    /** True if following {@code path} would not bring the NPC at least a block closer to (x, y, z). */
    @Unique
    private static boolean mtmixins$gotNowhere(Path path, double x, double y, double z, double distanceSqBefore) {
        if (path == null || path.isFinished()) {
            return true;
        }
        PathPoint end = path.getFinalPathPoint();
        if (end == null) {
            return true;
        }
        double dx = end.x + 0.5D - x;
        double dy = end.y - y;
        double dz = end.z + 0.5D - z;
        return dx * dx + dy * dy + dz * dz >= distanceSqBefore - 1.0D;
    }
}
