package com.novotimo.mtmixins.mixin.vanilla;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.novotimo.mtmixins.MedievalTimesMixins;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Register B15 &mdash; resolves entities that share a UUID, which players experience as
 * "my dragon despawned on restart, no corpse" and as whole herds vanishing after a laggy session.
 *
 * <h2>This is a known vanilla bug, and this is the known fix</h2>
 *
 * <p>It is <a href="https://bugs-legacy.mojang.com/browse/MC-101734">MC-101734</a>, and it has been
 * hit hard enough elsewhere to produce an established remedy. PaperMC worked it through in
 * <a href="https://github.com/PaperMC/Paper/issues/1223">issue #1223</a> and shipped a
 * {@code duplicate-uuid-resolver} setting whose default mode, {@code SAFE_REGEN}, is what this mixin
 * reimplements. On the modded side the same policy ships as the DEUF mod ("changes UUIDs of loaded
 * entities in case their UUIDs are already assigned"), which supports 1.16 through 1.21 but
 * <b>has no 1.12.2 build</b> &mdash; hence doing it here rather than installing it.
 *
 * <p>Mojang's own fix was not a patch but an architectural change: in 1.17 (snapshot 20w45a)
 * entities were lifted out of terrain chunk NBT into their own region files with a UUID-keyed
 * manager. That is not backportable to 1.12.2, so the community resolver is the right thing to
 * adopt.
 *
 * <h2>Two causes, which is why the fix is not "delete the duplicate"</h2>
 *
 * <p>Both of these are present in this world, and a single scan of the overworld's 2,847 region
 * files (2.6 million entities, 51 duplicated UUIDs) separates them by how far apart the copies are:
 *
 * <ol>
 *   <li><b>Genuinely different entities that collided on a UUID at creation.</b> Paper traced this
 *       to a shared {@code Random} with a predictable seed handing out repeated UUIDs. Here, one
 *       pair of fire dragons sharing a UUID sits <b>10,957 blocks apart</b>; four duplicates have
 *       every copy more than 32 blocks from the others. Deleting one of these destroys a real
 *       animal.</li>
 *   <li><b>One entity written into two chunks.</b> In {@code World.updateEntityWithOptionalForce}
 *       the removal of an entity from the chunk it is leaving is guarded by
 *       {@code isChunkLoaded(entity.chunkCoordX, entity.chunkCoordZ, true)}; if that old chunk is
 *       not loaded at that moment the removal is skipped, so the old chunk keeps a copy and the new
 *       chunk gains one. 36 of the 51 duplicates have all copies within 32 blocks, and the worst
 *       case &mdash; one fire dragon, 84 copies, many at <i>identical</i> coordinates &mdash; cannot
 *       be 84 animals.</li>
 * </ol>
 *
 * <p>Either way the copy is permanent, because of a second vanilla detail:
 * {@code AnvilChunkLoader.readChunkEntity} calls {@code chunk.addEntity(entity)} <i>before</i> the
 * world gets a say, and {@code WorldServer.loadEntities} then refuses the duplicate for the
 * <i>world</i> list while leaving it in the <i>chunk</i> list. The next save writes it straight back.
 * So vanilla refuses it forever and never resolves it, which is why one dragon produced 8,720 log
 * lines in ten days and why which copy appears is a load-order race.
 *
 * <h2>What this does</h2>
 *
 * <p>Before vanilla's loop runs, for each incoming entity whose UUID is already live:
 *
 * <ul>
 *   <li><b>Same type and within {@value #SAFE_RANGE} blocks</b> &mdash; treat it as the same entity
 *       written twice: drop it <i>and</i> take it out of the chunk, so it is not saved again and
 *       does not come back on the next load.</li>
 *   <li><b>Otherwise</b> &mdash; treat it as a distinct entity that collided: give it a fresh UUID
 *       and let it join the world normally. Nothing is destroyed.</li>
 * </ul>
 *
 * <p>The range matches Paper's default. The bias is deliberate: an unnecessary extra animal is a
 * cheap mistake, deleting someone's dragon is not.
 *
 * <p><b>The trade-off, stated plainly.</b> On a duplicate whose copies are spread out, such as that
 * 84-copy dragon, the near copies are removed and a far one may be re-identified into a second live
 * dragon. That is the documented behaviour of this policy and it is the safe direction, but it does
 * mean an occasional surprise animal rather than a silent loss.
 *
 * <p>This heals the world as chunks load, with no offline pass and no downtime: each affected chunk
 * is fixed the first time a player goes near it. {@code tools/dupfix.py} does the same job offline
 * for anyone who can take the world down, which on a managed host is usually nobody.
 *
 * <p>Kept in its own mixin config ({@code mixins.mtmixins.entitydupe.json}) so it can be switched
 * off from {@code config/mixinbooter.cfg} without a rebuild, because it is the only mixin in this
 * mod that rewrites save data.
 */
@Mixin(WorldServer.class)
public abstract class MixinWorldServerEntityDupe {

    /**
     * Blocks. Within this distance, two same-type entities sharing a UUID are taken to be one
     * entity saved twice; beyond it, two different entities that collided. 32 is Paper's default
     * {@code duplicate-uuid-saferegen-delete-range} and it separates this world's data cleanly:
     * 36 duplicates fall entirely inside it, 4 entirely outside.
     */
    @Unique
    private static final double SAFE_RANGE = 32.0D;

    /**
     * The same question, asked much more strictly, for entities that a player placed or owns.
     *
     * <p>32 blocks was wrong for these and the production logs showed it: between 1 and 3 October
     * this register deleted 4 CustomNPCs NPCs, 11 AncientWarfare NPCs including two faction traders,
     * 6 paintings, 6 horses, a skeleton horse, two vehicles and a gate &mdash; 31 things somebody had
     * put there on purpose.
     *
     * <p>The reason 32 blocks fails here is that the type half of the test does no work.
     * <b>CustomNPCs registers every NPC it creates under the single entity id
     * {@code customnpcs:customnpc}</b>, so a merchant, a guard and a dialogue NPC are
     * indistinguishable by type, and "same type and within 32 blocks" is then just "within 32
     * blocks", which in a town is always true. Six paintings on one wall and six horses in one
     * stable have the same problem. Worse, these are exactly the entities most likely to hit the
     * UUID-collision cause rather than the written-twice cause: Paper traced collisions to a shared
     * {@code Random}, so entities created in the same tick collide with each other &mdash; and
     * entities created in the same tick are usually standing in the same room.
     *
     * <p>At 1 block the test means what it was always supposed to mean: two copies at the same spot
     * are one entity saved twice, and anything further apart is treated as a distinct entity and
     * given a fresh UUID instead of being destroyed. That still cleans up the unambiguous case
     * &mdash; the pair of {@code aw_npc_combat} at identical coordinates on 1 October, for instance
     * &mdash; while leaving a merchant alone.
     */
    @Unique
    private static final double PLACED_RANGE = 1.0D;

    /**
     * Entity ids whose copies must not be deleted on a near miss. Prefixes, matched against the
     * registry name, so {@code ancientwarfarenpc:} covers every faction role at once.
     *
     * <p>What belongs here is anything a player placed, owns, or built, and anything whose entity id
     * is shared across many distinct things. What does not belong here is mobs and transient
     * entities, which is where almost all of the real duplicate damage was: of the 802 resolutions
     * in the production window, 568 were falling blocks, items and arrows.
     */
    @Unique
    private static final String[] PLACED_PREFIXES = {
            "customnpcs:",                      // one entity id for every NPC the mod makes
            "ancientwarfarenpc:",                // faction traders, soldiers, archers, engineers
            "ancientwarfarevehicle:",
            "ancientwarfarestructure:",          // gates
            "minecraft:painting",
            "minecraft:item_frame",
            "minecraft:armor_stand",
            "minecraft:horse",
            "minecraft:skeleton_horse",
            "minecraft:zombie_horse",
            "minecraft:donkey",
            "minecraft:mule",
            "minecraft:llama",
            "minecraft:boat",
            "minecraft:minecart",
            "minecraft:chest_minecart",
            "minecraft:hopper_minecart",
            "minecraft:furnace_minecart",
            "minecraft:tnt_minecart",
            "minecraft:commandblock_minecart",
            "minecraft:leash_knot",
    };

    @Unique
    private static long mtmixins$protectedKept = 0L;

    @Unique
    private static long mtmixins$unloadRaces = 0L;

    /**
     * True when this entity is already on its way out, so an incoming copy of it is itself.
     *
     * <p>Reached through {@link AccessorWorldUnloadQueue} rather than a {@code @Shadow}: the field is
     * declared on {@code World} and shadowing it from a {@code WorldServer} mixin compiles with a
     * warning and no obfuscation mapping, which binds in a dev run and fails on a real server.
     */
    @Unique
    private static boolean mtmixins$pendingUnload(WorldServer world, Entity live) {
        List<Entity> queue = ((AccessorWorldUnloadQueue) world).mtmixins$unloadedEntityList();
        return queue != null && queue.contains(live);
    }

    /**
     * True when this entity is a thing somebody placed or owns rather than ambient wildlife.
     *
     * <p>Kept as a second line of defence rather than as the fix. The unload-race check above is
     * what was actually wrong; this narrows the window for anything that slips past it, and costs
     * nothing but an occasional extra animal. No established fix for MC-101734 has a list like this
     * &mdash; DEUF only ever regenerates a UUID and never deletes, Paper deletes but downstream of
     * the check above, and Mojang's own answer in 1.17 was to move entities out of chunk NBT
     * entirely &mdash; so if this list ever looks like it is doing real work, that is a sign
     * something upstream of it is wrong again.
     */
    @Unique
    private static boolean mtmixins$isPlaced(ResourceLocation id) {
        if (id == null) {
            return true;    // unknown is treated as precious
        }
        String s = id.toString();
        for (String prefix : PLACED_PREFIXES) {
            if (s.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static final Set<UUID> mtmixins$reported = new HashSet<UUID>();

    @Unique
    private static long mtmixins$dropped = 0L;

    @Unique
    private static long mtmixins$reidentified = 0L;

    /**
     * {@code HEAD}, not a wrap of the {@code canAddEntity} call: that method is private, and
     * repeating its one map lookup is easier to reason about than injecting into an
     * {@code invokespecial}. Acting before vanilla's loop is also what lets the re-identify branch
     * work &mdash; by the time the entity has a fresh UUID, vanilla's own check passes and it is
     * added normally.
     */
    @Inject(method = "loadEntities", at = @At("HEAD"))
    private void mtmixins$resolveDuplicateUuidsOnLoad(Collection<Entity> entityCollection,
                                                      CallbackInfo ci) {
        if (entityCollection == null || entityCollection.isEmpty()) {
            return;
        }
        WorldServer self = (WorldServer) (Object) this;

        // Entities seen earlier in THIS batch, which getEntityFromUuid cannot tell us about.
        //
        // This injects at the head of loadEntities, before any of the chunk's entities have been
        // added to the world, so getEntityFromUuid only ever knows about entities that came from a
        // chunk loaded earlier. That made the fix blind to the case that matters most here: a single
        // chunk whose own list holds many copies of one UUID. One chunk on this server held 83 copies
        // of the same dragon - damage from the pre-B16 horn, which released a dragon carrying the
        // stored UUID every time it was used - and every one of them fell through the live-entity
        // check and was left alone. They sat in the chunk list refused by canAddEntity, never ticking,
        // which is why their positions were frozen at exact block centres, and /tellme counted all 83
        // because it reads chunk lists rather than the world's UUID map.
        Map<UUID, Entity> seenInBatch = new HashMap<UUID, Entity>();

        // Copy first: the delete branch touches the chunk entity lists this may be backed by.
        for (Entity incoming : new ArrayList<Entity>(entityCollection)) {
            if (incoming == null || incoming.isDead) {
                continue;
            }
            UUID uuid = incoming.getUniqueID();
            if (uuid == null) {
                continue;
            }
            Entity live = self.getEntityFromUuid(uuid);

            // THE CHECK THIS REGISTER ORIGINALLY MISSED, AND THE REASON IT DELETED 31 THINGS
            // PLAYERS HAD PLACED.
            //
            // getEntityFromUuid reads entitiesByUuid, and an entity queued for unload STAYS in
            // that map until World.updateEntities drains unloadedEntityList at the end of the
            // tick. Chunk loading happens during the tick, before that drain. So when a chunk
            // unloads and is loaded again in the same tick - which happens constantly, and P-08
            // shows how many things ask for chunks mid-tick - the copy read back off disk collides
            // with its own pending-unload self. Same type, same coordinates, distance zero.
            //
            // Vanilla handles this explicitly. WorldServer.canAddEntity:
            //
            //     if (this.unloadedEntityList.contains(entity)) {
            //         this.unloadedEntityList.remove(entity);   // cancel the unload
            //     } else {
            //         ... "Keeping entity {} that already exists with UUID {}"; return false;
            //     }
            //     this.removeEntityDangerously(entity);         // drop the stale copy, keep this one
            //
            // and it logs nothing, which is why the production logs showed 188 duplicate UUIDs in
            // ten days from vanilla and 795 in three days from this register. Those were not
            // duplicates. They were ordinary chunk churn, and 568 of the 802 were falling blocks
            // and dropped items - entities with no plausible reason to collide on a UUID at that
            // rate, which is the tell.
            //
            // Paper's SAFE_REGEN does the same same-type-and-32-blocks test this register copied,
            // but it runs it on the far side of this branch, as part of the entity-add path rather
            // than ahead of it. Lifting the decision rule without its position is what broke it;
            // the rule was never the problem.
            if (live != null && mtmixins$pendingUnload(self, live)) {
                mtmixins$unloadRaces++;
                // Logged on its own rather than only inside mtmixins$report, because this branch
                // returns before any report runs - so a server where EVERY case is an unload race
                // printed nothing at all, and "it worked" and "it never loaded" looked identical in
                // the log. First one, then every hundredth, so it stays measurable without
                // reproducing the spam it replaced.
                if (mtmixins$unloadRaces == 1L || mtmixins$unloadRaces % 100L == 0L) {
                    MedievalTimesMixins.LOG.warn(
                            "Chunk unload race, not a duplicate: {} (UUID {}) at {},{},{} in {} was "
                                    + "reloaded while its own copy was still queued for unload. "
                                    + "Left alone. {} of these so far; vanilla handles them and logs "
                                    + "nothing. Before register B15 was corrected these were being "
                                    + "deleted. See MC-101734.",
                            EntityList.getKey(incoming), uuid,
                            (int) incoming.posX, (int) incoming.posY, (int) incoming.posZ,
                            self.provider.getDimensionType().getName(),
                            mtmixins$unloadRaces);
                }
                continue;
            }

            if (live == null) {
                live = seenInBatch.get(uuid);
            }
            if (live == null) {
                seenInBatch.put(uuid, incoming);
                continue;
            }
            if (live == incoming) {
                continue;
            }

            if (mtmixins$isSameEntitySavedTwice(live, incoming)) {
                Chunk chunk = self.getChunkProvider()
                        .getLoadedChunk(incoming.chunkCoordX, incoming.chunkCoordZ);
                if (chunk != null) {
                    chunk.removeEntity(incoming);
                    chunk.markDirty();
                }
                incoming.setDead();
                try {
                    entityCollection.remove(incoming);
                } catch (UnsupportedOperationException immutable) {
                    // Then vanilla logs its own warning for it; the chunk prune still happened.
                }
                mtmixins$dropped++;
                mtmixins$report(incoming, uuid, self, "dropped a second copy of");
            } else {
                incoming.setUniqueId(UUID.randomUUID());
                Chunk chunk = self.getChunkProvider()
                        .getLoadedChunk(incoming.chunkCoordX, incoming.chunkCoordZ);
                if (chunk != null) {
                    chunk.markDirty();
                }
                // Keep the batch map pointing at something that still holds the original UUID, so a
                // third copy is still measured against the first rather than against an entity that
                // has just been given a different identity.
                if (!seenInBatch.containsKey(uuid)) {
                    seenInBatch.put(uuid, live);
                }
                mtmixins$reidentified++;
                mtmixins$report(incoming, uuid, self, "gave a fresh UUID to");
            }
        }
    }

    /**
     * True when the two are the same animal written to disk twice rather than two animals that
     * happened to be handed the same UUID. Type must match, and they must be close enough that one
     * could not plausibly have wandered there as a separate creature.
     */
    @Unique
    private static boolean mtmixins$isSameEntitySavedTwice(Entity live, Entity incoming) {
        ResourceLocation a = EntityList.getKey(live);
        ResourceLocation b = EntityList.getKey(incoming);
        if (a == null || b == null || !a.equals(b)) {
            return false;
        }
        double range = (mtmixins$isPlaced(a) || mtmixins$isPlaced(b))
                ? PLACED_RANGE : SAFE_RANGE;
        boolean same = live.getDistanceSq(incoming) <= range * range;
        if (!same && range == PLACED_RANGE) {
            mtmixins$protectedKept++;
        }
        return same;
    }

    /** One line per UUID, so a single stuck animal does not reproduce the spam it is fixing. */
    @Unique
    private static void mtmixins$report(Entity entity, UUID uuid, WorldServer world, String what) {
        if (mtmixins$reported.size() >= 512 || !mtmixins$reported.add(uuid)) {
            return;
        }
        MedievalTimesMixins.LOG.warn(
                "Duplicate UUID resolved: {} {} (UUID {}) at {},{},{} in {}. Totals this session: "
                        + "{} dropped, {} re-identified, {} protected by distance, {} unload races skipped. "
                        + "See register B15 / MC-101734.",
                what, EntityList.getKey(entity), uuid,
                (int) entity.posX, (int) entity.posY, (int) entity.posZ,
                world.provider.getDimensionType().getName(),
                mtmixins$dropped, mtmixins$reidentified, mtmixins$protectedKept,
                mtmixins$unloadRaces);
    }
}
