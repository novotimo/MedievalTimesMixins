# Bug fix report

Baseline: `96cc13c` ("Dragon claim protection and breath damage policy"). Fixes are on branch
`claude/tender-edison-ygomlf`.

**Before** means a jar built from `96cc13c`. **After** means a jar built from this branch
(`./gradlew build`, deploy `build/libs/mtmixins-0.2.0.jar`).

**Harness.** `tools/mixin-harness/run.sh [baseline-rev]` builds the mixins from the baseline and from
the working tree, applies each build with the real Mixin 0.8.7 transformer and MixinExtras 0.5.5 to
stand-in classes that have the same method shapes as the real targets, and runs one test per fix. It
needs JDK 9+ and Maven Central on the first run, and takes about 20 s. A bug shows as `FAIL` on the
baseline and `PASS` on the working tree. Every regression case must `PASS` on both.

---

## 1. B18: collision counter never clears for minecarts, so they become unpushable

**Files:** `mixin/vanilla/MixinEntityCollisionCount.java`, `mixin/vanilla/MixinEntityLivingBaseCollisions.java` (javadoc only)

**Defect.** The per-tick push count was reset by an `@Inject` at HEAD of `Entity.onUpdate`. Two cases
never reach that point:

- `EntityMinecart.onUpdate` never calls `super.onUpdate()`.
- Entities outside the area the world ticks are never updated at all.

`MixinEntityLivingBaseCollisions` refuses a push when *either* side is at the cap, so once such an
entity reaches 8 pushes it can never be pushed by a living entity again until its chunk reloads. A
stationary minecart only collides with other minecarts in its own tick, so walking into it does
nothing.

There is a second effect: a ticking entity's count was wiped at its own update, which discarded the
pushes it received earlier in the same tick. One entity could take part in up to 2× the cap per tick.

**Upstream.** Paper 1.12.2, `Spigot-Server-Patches/0199-Cap-Entity-Collisions.patch`, gates only the
pusher. At the start of the pusher's own pass it sets
`numCollisions = Math.max(0, numCollisions - max)` and never reads the pushed entity's count, so it has
no reset to miss. The javadoc said the symmetric gate and the `onUpdate` reset were Spigot's. That was
wrong, and the javadoc is now corrected. The symmetric gate is kept as a deliberate local choice.

**Fix.** The count is stored together with `world.getTotalWorldTime()` and reads as 0 in any other
tick. The `onUpdate` inject is removed.

**Reproduce (before).**
1. Run a server or singleplayer world with the baseline jar. Make sure there is no
   `config/mtmixins-collisions.properties`, so the cap is the default 8.
2. On flat ground with no rails, run `/summon minecraft:minecart ~3 ~ ~`.
3. Walk into the cart. It slides for the first few contacts (8 ticks of contact, under half a second),
   then stops reacting to players and mobs. It stays stuck until the chunk unloads and reloads, after
   which it slides for 8 more ticks of contact.
4. Harness: `CollisionTest` prints `minecart pushed 8 times (expected 20)` → `FAIL`, and
   `pushes per tick ... max 297` (above the 40×8/2 = 160 bound) → `FAIL`.

**Verify (after).**
1. Repeat steps 1–3. The cart slides on every contact, indefinitely.
2. Harness: `CollisionTest` shows `minecart: PASS` (20/20) and `pen: PASS` (156 pushes per tick for
   40 cows, ≤ 160; no entity above 8).
3. Regression:
   - `max-entity-collisions = 0` in `config/mtmixins-collisions.properties`: mobs do not push each
     other.
   - `max-entity-collisions = -1`: vanilla pushing.
   - Cramming: run `/gamerule maxEntityCramming 24` and put 30 cows in a 1×1 pit. They still take
     cramming damage (that code runs before the wrapped call and is untouched).
   - With the pit loaded, `/spark profiler --timeout 60` on baseline and on the branch: time under
     `EntityLivingBase.collideWithNearbyEntities` is the same or lower on the branch, and far higher
     with `-1`.

---

## 2. B15: unload race skipped duplicate resolution for the rest of the slice

**File:** `mixin/vanilla/MixinWorldServerEntityDupe.java`

**Defect.** When the world's copy of a UUID was queued for unload, the loop hit `continue` before it
consulted `seenInBatch`. Every copy of that UUID in the same entity slice then went to vanilla.
Vanilla swapped in the first copy and refused the rest with
`Keeping entity <id> that already exists with UUID <uuid>`. The refused copies stay in the chunk's
entity list and are saved again, which is the permanent duplicate B15 exists to remove.

**Upstream.** Paper 1.12.2, `Spigot-Server-Patches/0338-Duplicate-UUID-Resolve-Option.patch`:
`if (other == null || other.dead || world.getEntityUnloadQueue().contains(other)) other = thisChunk.get(entity.uniqueID);`

**Fix.** Same ordering as Paper. A world copy that is queued for unload is treated as absent, then the
batch map is checked. The unload-race log line moved into `mtmixins$noteUnloadRace` with unchanged
text and rate. It now counts a race only when no earlier copy in the batch claims the UUID.

**Reproduce (before).**
1. This cannot be triggered on demand in game. It needs a chunk to unload and reload in the same tick
   while it holds ≥ 2 copies of one UUID in one 16-block slice and another copy of that UUID is queued
   for unload.
2. Live signature: a `Chunk unload race, not a duplicate` line for a UUID, followed by vanilla's
   `Keeping entity ... that already exists with UUID <same uuid>`. The UUID is still in that chunk
   twice on the next load (check with `/tellme` or NBTExplorer).
3. Harness: `DupeTest` prints `unload race + 2 copies in slice: FAIL - chunk list now 2 entities ... Keeping entity`.

**Verify (after).**
1. Harness: `DupeTest` prints `unload race + 2 copies in slice: PASS - chunk list now 1 entities`. The
   only vanilla warning is `Tried to add entity ... but it was marked as removed already` for the
   dropped copy, the same line every B15 drop already produces.
2. Regression, all `PASS` on baseline and branch:
   - Plain unload race: vanilla swap, nothing dropped, no warnings.
   - Live nearby duplicate: dropped and pruned from the chunk.
   - Live distant duplicate: re-identified, both kept.
   - Three copies in one slice with no race: two dropped.
3. On the server, `Duplicate UUID resolved` and `Chunk unload race` lines keep their existing format.
   The `unload races skipped` total no longer counts the extra copies.

---

## 3. B2b: OreVeins biome cache returned another world's biome

**Files:** `util/ChunkBiomeCache.java`, `mixin/oreveins/MixinWorldGenVeins.java`

**Defect.** `WorldGenVeins` is a single `IWorldGenerator` shared by every dimension, and on an
integrated server it lives for the whole JVM. The cache key was chunk coordinates only. A column in
world B was therefore answered with world A's biome whenever that cache slot last held the same chunk
coordinates from A. `matchesBiome` then accepted or rejected veins against the wrong biome. Affected
cases:

- A vein enabled in several dimensions.
- Any two dimensions generating the same coordinates.
- In singleplayer, every save opened after the first in one session.

**Fix.** Each entry is stamped with a per-world generation object that holds the `World` weakly, so it
never keeps a world alive. A lookup from a different world is a miss. The lock-free immutable-entry
design is unchanged.

**Reproduce (before).**
1. In game the result depends on generation order and is not deterministic. Concrete form: give a
   vein `"dimensions": [0, -1]`, `"biomes": ["hell"]`, ore `minecraft:gold_block`, and stone
   `minecraft:stone` plus `minecraft:netherrack`. Generate the same coordinates in the Nether and the
   Overworld alternately (two players at the same X/Z, or a pregenerator running both dimensions at
   once). Gold blocks appear in Overworld stone, which is impossible because the Overworld has no
   `hell` biome. Some Nether chunks also miss veins they should have.
2. Harness: `OreVeinsTest` prints `matchesBiome saw 0 x hell, 256 x forest (expected 256 x hell)` →
   `cross-world: FAIL`.

**Verify (after).**
1. Harness: `cross-world: PASS` (256 × hell). `same-world cache: PASS`: back in the first world, one
   pass of 256 `getBiome` calls, then a repeat pass with 0 calls, all answers correct. The cache still
   works.
2. Regression for single-world output: in a fresh JVM each time, create a world from a fixed seed and
   pregenerate the same area with the baseline jar and with the branch jar. Ore counts in the area
   (TellMe `blockstats`, or MCA Selector) are identical. Single-world lookups return what they did
   before.

---

## 4. B13: Lycanites `block` and `material` spawners still generated chunks

**Files:** `mixin/lycanites/MixinBlockSpawnLocation.java`, `mixin/lycanites/MixinMaterialSpawnLocation.java` (new), `mixins.mtmixins.cascade.json`

**Defect.** The guard at HEAD of `BlockSpawnLocation.isValidBlock` only covered `RandomSpawnLocation`,
which calls `super.isValidBlock` on bare columns. Two other paths were missed:

- `BlockSpawnLocation.getSpawnPositions` calls `world.getBlockState(pos)` on every candidate to skip
  flowing liquids, *before* `isValidBlock`. That read loads or generates the chunk, so the guard never
  fired on this path. This is true of every Lycanites 1.12.2 version from 2019-06-11 ("Block Spawn
  Location Fix") through community 2.0.8.10 (GitLab `Lycanite/LycanitesMobs`, branch
  `Minecraft-1.12.2`).
- `MaterialSpawnLocation` overrides `isValidBlock` without calling `super` and opens with its own
  `getBlockState`.

The stock `lava`, `fire` and `mineshaft` spawners are `"type": "block"` with a 65×65×65 sweep around
the player.

**Fix.** A `@WrapOperation` on `World.getBlockState` inside `getSpawnPositions` returns air for an
unloaded position, and `isValidBlock` then rejects it. `MaterialSpawnLocation.isValidBlock` gets the
same HEAD guard.

**Reproduce (before).**
1. On a server with the baseline jar and Lycanites, start `/spark profiler --thread "Server thread"`.
2. Repeatedly teleport into ungenerated terrain next to lava (for example `/tp @p 20000 80 20000`, then
   a new location each time), so spawner sweeps run before the surrounding chunks have loaded.
3. Stop the profiler. Stacks
   `BlockSpawnLocation.getSpawnPositions → World.getBlockState → ChunkProviderServer.provideChunk`
   (load or generate) are present.
4. Harness: `LycanitesTest` prints `BlockSpawnLocation sweep: FAIL` and
   `MaterialSpawnLocation sweep: FAIL`, both with chunks `[-1,-1 … 1,1]` loaded by the scan.
   `RandomSpawnLocation: PASS`.

**Verify (after).**
1. Same profile: no `provideChunk` under `getSpawnPositions` or `MaterialSpawnLocation.isValidBlock`.
2. Harness: all three `PASS`. The sweeps load 0 chunks and return 768 positions, exactly the candidates
   inside the loaded chunk. `RandomSpawnLocation` returns the same 4 positions as on the baseline.
3. Regression: in loaded terrain, lava, fire and mineshaft spawns occur at the same rate as on the
   baseline (watch a known spot for 10 minutes on each build).

---

## 5. lootattrib: shared loot table's name grew on every lookup

**File:** `mixin/vanilla/MixinLootTableManagerName.java`

**Defect.** Every missing table resolves to the single shared `LootTable.EMPTY_LOOT_TABLE`. After it
had been asked for under two names, its name no longer matched any name, so every later lookup
appended `"<shared: " + name + " and " + location + ">"`. This runs on every mob death and every loot
chest. The string grows without bound and each append copies all of it. The cost is quadratic CPU and
heap growth on the server thread for as long as `mixins.mtmixins.lootattrib.json` is loaded.

**Fix.** The table is marked shared once (`<shared: a, b and possibly others>`) and then left alone.

**Reproduce (before).**
1. Run `/summon minecraft:zombie ~ ~ ~ {DeathLootTable:"mtmixins:missing_a"}`, then
   `/kill @e[type=zombie]`.
2. Repeat step 1 with `mtmixins:missing_b`.
3. Set up a repeating command block running
   `/summon minecraft:zombie ~ ~2 ~ {DeathLootTable:"mtmixins:missing_a",NoAI:1b}`, chained to
   `/kill @e[type=zombie]`. Leave it for 20 minutes.
4. `/spark profiler --timeout 60` shows `MixinLootTableManagerName` / `getLootTableFromLocation` →
   `StringBuilder` / `Arrays.copyOf` growing, and MSPT rises steadily.
5. Harness: `LootNameTest` reports a name of `559985 chars` after 20,000 lookups, taking about 7 s →
   `FAIL`.

**Verify (after).**
1. Same steps: MSPT stays flat and nothing from this mixin shows in the profile.
2. Harness: 58 chars, about 5 ms → `PASS`. A real table is still stamped with its own name
   (`real table still named: mod:chest`).

---

## 6. B20: "% of capacity" in the loot-fill log was computed against the wrong container

**File:** `mixin/vanilla/MixinLootTableShuffle.java`

**Defect.** The occupancy log divided the running average of slots used by the slot count of
whichever container happened to be the 1st, 100th, 200th, ... fill. With mixed container sizes the
figure is meaningless; it can exceed 100%. This is the number used to check the 40–65% target. The
`wouldHaveOverfilled` counter was incremented but never printed.

**Fix.** Capacity is summed alongside usage, giving total slots used ÷ total slots offered. The counter
is printed as `N fills used every slot`.

**Reproduce (before).**
1. Harness: `LootStatsTest` fills a 27-slot container 99 times and a 12-slot container once. Ground
   truth is 1422 of 2685 slots = 53%. The baseline logs `... 14.2 slots, 119% of capacity` at fill 100.
2. On the server, any `Loot fill:` line above 100% is this bug.

**Verify (after).**
1. Harness: the branch logs `... 14.2 slots, 53% of capacity; 0 fills used every slot`, which matches
   the ground truth.
2. Regression: both runs produce the identical 1422/2685 placement, so loot behaviour is unchanged.
   Only the log line differs.

---

## 7. `@Mod` version said 0.1.0 in a 0.2.0 build

**File:** `MedievalTimesMixins.java`

**Defect.** `VERSION = "0.1.0"`, while `gradle.properties` has `mod_version = 0.2.0` (the bump was in
`98d3f37`). FML takes the version from the `@Mod` annotation, not from `mcmod.info`. The client Mods
list, the FML handshake and crash-report mod tables therefore report 0.1.0 for the 0.2.0 jar.

**Fix.** `VERSION = "0.2.0"`, with a comment that it must track `mod_version`.

**Reproduce (before).** Build the baseline. The jar is named `mtmixins-0.2.0.jar`, but the client
Mods screen shows `Medieval Times Mixins 0.1.0`. The mod state table in any crash report or
`fml-*-latest.log` lists `mtmixins{0.1.0}` next to `mtmixins-0.2.0.jar`.

**Verify (after).** The same places show 0.2.0. `acceptableRemoteVersions = "*"`, so mixed
client/server versions still connect.

---

## 8. DragonBridge fail-closed messages said "inside claims"

**File:** `bridge/DragonBridge.java`

**Defect.** When Civilizations is present but `DragonPolicy` lacks the expected methods, or
`blockChangeAllowed` throws, `blockChangeAllowed` returns `false` for every block in every location,
since there is no policy to tell claimed land from unclaimed. The two log messages said dragon block
changes would be refused "inside claims". An operator reading the log would not expect dragons to stop
breaking blocks on unclaimed land too.

**Fix.** Both messages now say every block change is refused, claimed or not. Behaviour is unchanged.

**Reproduce (before).** Run with a Civilizations build whose `me.qourtenay.Civilizations.compat.DragonPolicy`
lacks `blockChangeAllowed(Object, Object, Object)` (or temporarily rename it). The log says
"...REFUSE all dragon block changes inside claims...". A dragon breathing on unclaimed land breaks
nothing.

**Verify (after).** Same setup. The log says dragons will be refused every block change everywhere.
Dragons on unclaimed land still break nothing, exactly as before.
