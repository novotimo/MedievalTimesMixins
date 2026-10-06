# Bug fix report

Fixes 1–8 were merged in PR #1 (baseline `96cc13c`). Fix 9 is in this branch.

Every bug below has numbered in-game steps. Run them once with the **baseline** jar (bug shows) and
once with the **fixed** jar (bug gone). The regression steps must give the same result on both jars.
Section 10 is the same check done offline with the harness.

## 0. Setup

### 0.1 Build both jars (PowerShell, repo root)

```powershell
git restore --source 96cc13c --worktree -- src      # sources as they were before the fixes
.\gradlew.bat build
Copy-Item build\libs\mtmixins-0.2.0.jar ..\mtmixins-BASELINE.jar
git restore --worktree -- src                       # back to the current sources
.\gradlew.bat build
Copy-Item build\libs\mtmixins-0.2.0.jar ..\mtmixins-FIXED.jar
git status                                          # must be clean
```

`git restore --source` also deletes files that did not exist at `96cc13c`
(`MixinMaterialSpawnLocation.java`), so the baseline jar is exactly the old code.

### 0.2 Test server

- Use a copy of the world, or a new world. Several steps edit terrain or region files.
- To switch builds: stop the server, put exactly one of the two jars in `mods\`, then start it.
- You need op level 4 (`/forge gen` requires it; it is the dedicated-server default). Type all commands
  in chat.
- `config\mixinbooter.cfg` must not blacklist any `mixins.mtmixins.*.json`.
- Unless a step says otherwise, delete `config\mtmixins-collisions.properties` so the collision cap is
  the default 8.

---

## 1. B18: a minecart stops being pushable after 8 pushes

**Files:** `mixin/vanilla/MixinEntityCollisionCount.java`, `mixin/vanilla/MixinEntityLivingBaseCollisions.java`

**Defect.** The per-tick push counter was reset at the head of `Entity.onUpdate`, but
`EntityMinecart.onUpdate` never calls `super.onUpdate()`, so a minecart's counter only ever went up. A
push is refused when *either* entity is at the cap. After 8 pushes the cart therefore refused every
push from a player or mob until its chunk unloaded. A stationary cart only collides with other carts in
its own tick, so nothing else moved it either.

**Fix.** The counter is stamped with `world.getTotalWorldTime()` and reads as 0 in any other tick.

**Reproduce (baseline jar).**
1. Stand on flat ground with no rails nearby.
2. Run `/summon minecraft:minecart ~3 ~ ~`.
3. Walk into the cart, back off, and repeat. It slides for the first one or two contacts (8 ticks of
   contact in total, under half a second), then stops reacting to being walked into, from any side.
4. It stays frozen until its chunk unloads (walk well past view distance, then come back) or the server
   restarts. After that it moves for another 8 ticks of contact. Near spawn the chunk never unloads,
   so the cart stays frozen until a restart.

**Expected (fixed jar).** Same steps: the cart slides on every contact, indefinitely. This is what
you saw (your log shows the 0.2.0 fixed build).

**Regression (same result on both jars).**
1. `/gamerule maxEntityCramming 24`. Dig a 1×1 hole two deep and run
   `/summon minecraft:cow <x> <y> <z>` 30 times on its floor. The cows take cramming damage.
2. Create `config\mtmixins-collisions.properties` containing `max-entity-collisions = 0`, then
   restart. Walking into a cow no longer moves it, and cows in the pit stop pushing each other.
3. Set `max-entity-collisions = -1` and restart. Pushing is vanilla.
4. Delete the file and restart. Walking into a cow pushes it normally.

---

## 2. B15: a chunk-unload race let duplicate entities through

**File:** `mixin/vanilla/MixinWorldServerEntityDupe.java`

**Defect.** When an incoming entity's UUID belonged to a world entity that was queued for unload, the
loop hit `continue` before checking the other copies in the same 16-block slice. All copies went to
vanilla. Vanilla swapped in the first copy and refused the rest with `Keeping entity ... that already
exists`, which leaves them in the chunk's entity list, so they are saved again. They survive until the
chunk's next load that is not itself a race; that load deletes them.

**Fix.** Same order as Paper's resolver: a queued-for-unload world copy counts as absent, and the copies
seen earlier in the batch are checked next.

**Why the steps work.** A race needs an entity to still be queued for unload when a chunk holding a copy
of it loads. Normally the queue is emptied at the end of the same tick. Forge's
`WorldServer.updateEntities` returns early once a world has had no players for 300 ticks (and has no
force-loaded chunks), so the queue is not emptied. With nobody in the Overworld, an entity unloaded
there stays queued, and `/forge gen` can then load the second chunk while it is still queued.

**One-time setup (both jars use the same prepared world).**
1. Choose a spot at least 400 blocks from world spawn. The commands below use x=2008. If your spawn is
   near there, add the same offset to every x coordinate in this section.
2. Run:
   ```
   /summon minecraft:armor_stand 2008 80 8 {CustomName:"E1",NoGravity:1b}
   /summon minecraft:armor_stand 2008 80 24 {CustomName:"E2",NoGravity:1b}
   /summon minecraft:armor_stand 2008 80 24 {CustomName:"E3",NoGravity:1b}
   ```
   E1 is in chunk A (125, 0). E2 and E3 are in chunk B (125, 1), at the same position and in the same
   16-block slice.
3. Go back to spawn, run `/save-all`, and stop the server. Log out at spawn, not near x=2008.
4. In NBTExplorer, open `world\region\r.3.0.mca`.
   - Chunk [29, 0] (world chunk 125, 0) → Entities: set E1's `UUIDMost` to `7` and `UUIDLeast` to `7`.
   - Chunk [29, 1] → Entities: do the same for E2 and E3.
   - Save.
5. Make two copies of this world folder, one for each jar.

**Reproduce (baseline jar).**
1. Start the server and join. You are at spawn, so neither chunk is loaded.
2. Run `/gamemode 3`, then `/forge setdim @p -1`. You are now in the Nether and the Overworld has no
   players.
3. Wait at least 20 seconds (300 ticks plus margin).
4. Run `/forge gen 2008 80 8 1 0` and wait for it to report completion. Chunk A loads (E1 joins the
   world) and is queued for unload; it unloads on the next tick, and E1 stays queued.
5. Run `/forge gen 2008 80 24 1 0`. Chunk B loads while E1 is still queued.
6. `logs\latest.log` now shows:
   ```
   [Medieval Times Mixins]: Chunk unload race, not a duplicate: minecraft:armor_stand (UUID 00000000-0000-0007-0000-000000000007) at 2008,80,24 ...
   [net.minecraft.world.WorldServer]: Keeping entity minecraft:armor_stand that already exists with UUID 00000000-0000-0007-0000-000000000007
   ```
7. Run `/save-all flush`, stop the server, and open chunk [29, 1] in NBTExplorer. It still holds two
   armor stands with UUID 7/7.

**Expected (fixed jar, second world copy, same steps).** Step 6 shows the race line, then
`Duplicate UUID resolved: dropped a second copy of minecraft:armor_stand (UUID 00000000-0000-0007-0000-000000000007) at 2008,80,24 in overworld. ...`
and vanilla's `Tried to add entity minecraft:armor_stand but it was marked as removed already`. There is
no `Keeping entity` line. Step 7 shows one armor stand in chunk [29, 1].

If step 6 shows no race line on either jar, something is force-loading Overworld chunks and keeping
entity updates running. Repeat on a new world.

**Regression (same result on both jars).**
1. *Plain race.* Same steps, but in setup step 4 delete E3 from chunk [29, 1]. Step 6 shows only the
   race line: no drop, no `Keeping entity`. Chunk [29, 1] keeps one armor stand.
2. *Duplicates without a race.* Same setup as the main test, but skip reproduce steps 2–3 and stay in the
   Overworld at spawn. Run both `/forge gen` commands. The log shows
   `Duplicate UUID resolved: dropped a second copy of minecraft:armor_stand ...` and no race line.

---

## 3. B2b: OreVeins used another world's biome

**Files:** `util/ChunkBiomeCache.java`, `mixin/oreveins/MixinWorldGenVeins.java`

**Defect.** `WorldGenVeins` is a single instance shared by every dimension. Its biome cache was keyed
by chunk coordinates only, so generating chunk (X, Z) in one dimension right after the same (X, Z) in
another reused the first dimension's biomes.

**Fix.** Cache entries are stamped with the world that wrote them, held weakly so an unloaded world can
still be freed. A lookup from any other world misses.

**Setup (both jars).** Create `config\oreveins\mtmixins_repro.json`:
```json
{
  "mtmixins_repro_nether_only": {
    "type": "cluster",
    "ore": "minecraft:sponge",
    "stone": ["minecraft:stone", "minecraft:netherrack"],
    "count": 6,
    "rarity": 1,
    "min_y": 32,
    "max_y": 64,
    "horizontal_size": 16,
    "vertical_size": 10,
    "density": 80,
    "dimensions": [0, -1],
    "biomes": ["NETHER"]
  }
}
```
This vein is allowed in the Overworld and the Nether, but only in biomes tagged `NETHER`. The Overworld
has none, so it can never legitimately place a block there. Dry sponge does not generate naturally.
Restart the server so OreVeins loads the file. Keep the player at spawn, nowhere near the coordinates
used below.

**Reproduce (baseline jar).** Use a fresh area (never visited, in either dimension).
1. `/forge gen 40000 64 40000 1 -1`. This generates and populates Nether chunk (2500, 2500) only. Wait
   for completion.
2. `/forge gen 40000 64 40000 1 0`. This does the same in the Overworld. Wait for completion.
3. Run `/tp @p 40016 100 40016` (Overworld), then
   `/fill 40008 32 40008 40023 64 40023 minecraft:air 0 replace minecraft:sponge`.
   It reports **N blocks filled with N > 0**: sponge in Overworld stone, placed by a vein that requires
   a Nether biome.

**Expected (fixed jar).** Use a different fresh area (for example 48000 everywhere you used 40000, and
48008/48023 in the `/fill`). Step 3 reports **No blocks filled**.

**Regression (same result on both jars).**
1. Run `/forge setdim @p -1 40016 100 40016` (use spectator mode, `/gamemode 3`, to avoid
   suffocating), then the same `/fill` in the Nether. N > 0, so the vein still generates where it
   should. On the fixed jar use the 48000 area.
2. Delete `config\oreveins\mtmixins_repro.json` when you are done.

---

## 4. B13: Lycanites `block` and `material` spawners still loaded or generated chunks

**Files:** `mixin/lycanites/MixinBlockSpawnLocation.java`, `mixin/lycanites/MixinMaterialSpawnLocation.java` (new), `mixins.mtmixins.cascade.json`

**Defect.** `BlockSpawnLocation.getSpawnPositions` calls `world.getBlockState()` on every candidate
*before* `isValidBlock`, so the guard on `isValidBlock` never saw an unloaded chunk.
`MaterialSpawnLocation` overrides `isValidBlock` without calling `super`. Only `random` locations were
covered. The stock `lava`, `fire` and `mineshaft` spawners are `block` locations.

**Fix.** Inside the sweep, `getBlockState` returns air for an unloaded position, and
`MaterialSpawnLocation.isValidBlock` gets the same guard.

**Setup (both jars).**
1. In `server.properties` set `view-distance=3` (restore it afterwards). The server then only loads
   chunks within 3 chunks of a player.
2. Create these three files in `config\lycanitesmobs\spawners\`:

   `mtrepro_block.json`
   ```json
   {"name": "mtrepro_block", "type": "spawner", "enabled": true, "enableWithoutMobs": true,
    "ignoreBiomes": true, "conditions": [], "triggers": [],
    "locations": [{"type": "block", "rangeMin": [0, 0, 0], "rangeMax": [80, 0, 80], "blocks": [], "listType": "blacklist"}]}
   ```
   `mtrepro_material.json`
   ```json
   {"name": "mtrepro_material", "type": "spawner", "enabled": true, "enableWithoutMobs": true,
    "ignoreBiomes": true, "conditions": [], "triggers": [],
    "locations": [{"type": "material", "rangeMin": [0, 0, 0], "rangeMax": [80, 0, 80], "materials": ["air"]}]}
   ```
   `mtrepro_random.json`
   ```json
   {"name": "mtrepro_random", "type": "spawner", "enabled": true, "enableWithoutMobs": true,
    "ignoreBiomes": true, "conditions": [], "triggers": [],
    "locations": [{"type": "random", "rangeMin": [0, 0, 0], "rangeMax": [80, 2, 80]}]}
   ```
   They have no triggers and no mobs, so they only ever run when you run `/lm spawner test`. A
   `block`/`material` sweep then checks every position within 80 blocks horizontally, at your Y level.
3. Restart the server.

**Reproduce (baseline jar).**
1. Go at least 300 blocks from spawn, so you are outside the always-loaded spawn chunks.
2. Run `/testforblock ~72 ~ ~ minecraft:stone`. It reports **"Cannot test for block outside of the
   world"**: that chunk (4–5 chunks away) is not loaded.
3. Run `/lm spawner test mtrepro_block`.
4. Run `/testforblock ~72 ~ ~ minecraft:stone` again **within 30 seconds**. It now names the block
   there (or says it was found): the sweep loaded the chunk, or generated it if it was new.
5. Walk 300 blocks further, then repeat steps 2–4 with `/lm spawner test mtrepro_material`. Same
   result.

**Expected (fixed jar).** Same steps: step 4 still says **"Cannot test for block outside of the world"**
for both spawners.

**Regression (same result on both jars).**
1. Repeat with `/lm spawner test mtrepro_random`. Step 4 still says "outside of the world" on *both*
   jars (that path was already guarded at baseline).
2. Stand next to a lava lake in loaded terrain and run `/lm spawner test lava`. Lycanites lava mobs spawn
   on both jars, so positions inside loaded chunks are still found.
3. Afterwards, delete the three `mtrepro_*.json` files and restore `view-distance`.

---

## 5. lootattrib: the shared empty loot table's name grew on every lookup

**File:** `mixin/vanilla/MixinLootTableManagerName.java`

**Defect.** Every missing loot table resolves to the single `LootTable.EMPTY_LOOT_TABLE`. Once it had
been looked up under two names, every later lookup appended to its name. The string grows without bound
and each append copies all of it, so tick time climbs steadily.

**Fix.** The table is marked shared once and then left alone.

**Reproduce (baseline jar).**
1. Run `/summon minecraft:zombie ~ ~ ~ {DeathLootTable:"mtmixins:missing_b",NoAI:1b}` then
   `/kill @e[type=zombie]`. This stamps the empty table as shared.
2. Run `/summon minecraft:armor_stand ~ ~1 ~ {CustomName:"lr",NoGravity:1b,Invisible:1b}` **20 times**.
3. Place a Repeating command block (Always Active) containing
   `/execute @e[type=armor_stand,name=lr] ~ ~ ~ summon minecraft:zombie ~ ~ ~ {DeathLootTable:"mtmixins:missing_a",NoAI:1b,Silent:1b}`.
   Behind it, place a Chain command block (Always Active) containing `/kill @e[type=zombie]`. That is
   20 lookups of a missing table per tick.
4. Run `/spark tps` (or `/forge tps`) right away, then every 2 minutes for 10 minutes. The tick time
   (MSPT) climbs every time and does not level off. Within roughly 10 minutes the server is falling
   behind ("Can't keep up!").
5. Break the command blocks to stop.

**Expected (fixed jar).** Same steps: MSPT stays flat for the whole 10 minutes.

**Regression (both jars): real tables are still named.** Use the hopper in fix 9's regression step. Its
over-fill line shows `table minecraft:chests/simple_dungeon`.

---

## 6. B20: the "% of capacity" in the loot-fill log used the wrong container

**File:** `mixin/vanilla/MixinLootTableShuffle.java`

**Defect.** Every 100th fill, the log line `Loot fill: ... Average occupancy over N fills: X slots,
Y% of capacity` divided the running average by **that one container's** slot count. With mixed
container sizes Y is meaningless and can exceed 100%.

**Fix.** Slots used ÷ slots offered, both summed over all fills. The line also prints the "every slot
used" counter, which used to be collected but never shown.

**Setup.** Restart the server and do not explore or open loot containers, so the fill count starts at
0 with your contraption.

**Reproduce (baseline jar).**
1. Pick two spots and run `/setblock <A> minecraft:chest` and `/setblock <B> minecraft:hopper`. Place
   them with no loot table, so Lootr does not convert the chest.
2. Build a command block line: a Repeating block (Always Active), then three Chain blocks (Always
   Active), with these commands in order:
   1. `/blockdata <A> {LootTable:"minecraft:chests/simple_dungeon"}`
   2. `/replaceitem block <A> slot.container.0 minecraft:air`
   3. `/blockdata <B> {LootTable:"minecraft:chests/jungle_temple_dispenser"}`
   4. `/replaceitem block <B> slot.container.0 minecraft:air`

   Each tick this fills the 27-slot chest once and then the 5-slot hopper once. `/replaceitem` triggers
   the fill. The fills alternate, so every even-numbered fill is the hopper.
3. Let it run for 5 seconds, then break the Repeating block.
4. In `logs\latest.log`, find the line containing `over 100 fills`. It reads
   `... stacks into 5 slots ... Average occupancy over 100 fills: ~8 slots, ~160% of capacity`.
   Above 100% is impossible.

**Expected (fixed jar).** Same steps: `... over 100 fills: ~8 slots, ~50% of capacity; 0 fills used
every slot`. That is slots used ÷ slots offered across chest and hopper fills.

If something else filled loot first, fill 100 may be a chest fill (`into 27 slots`). The baseline value
is then about 30% instead of about 50%. It is still wrong, just not impossible. Use the `over 200 fills`
line instead.

**Regression.** Both jars place the same number of stacks per fill (compare the `N stacks into M slots`
part of the lines). Only the percentage differs.

---

## 7. The `@Mod` version said 0.1.0 in the 0.2.0 build

**File:** `MedievalTimesMixins.java`

**Reproduce (baseline jar).** Start the server once, then the client: the Mods screen lists
`Medieval Times Mixins 0.1.0`, and FML writes `mtmixins{0.1.0}` next to `mtmixins-0.2.0.jar`.

**Expected (fixed jar).** `0.2.0`. Your attached log already shows the transition: line 2247 reads
`This world was saved with mod mtmixins version 0.1.0 and it is now at version 0.2.0`. The world was
last saved by the 0.2.0 baseline jar, which reported itself as 0.1.0.

**Regression.** `acceptableRemoteVersions = "*"`, so a client on either jar joins a server on either jar.

---

## 8. DragonBridge's fail-closed message said "inside claims"

**File:** `bridge/DragonBridge.java`

**Reproduce (baseline jar).**
1. Build Civilizations with `DragonPolicy.blockChangeAllowed` renamed (for example to
   `blockChangeAllowedX`) and deploy it.
2. Start the server. The log says
   `... Dragon block protection will REFUSE all dragon block changes inside claims as a safe default ...`.
3. On **unclaimed** land, run `/summon iceandfire:firedragon ~ ~ ~10` and provoke it into breathing fire.
   No blocks change. The message understated what happens.

**Expected (fixed jar).** The message says dragons will be refused every block change everywhere,
claimed or not. Behaviour is identical: no blocks change.

**Regression.** With the normal Civilizations jar the log shows
`Dragon claim policy resolved from me.qourtenay.Civilizations.compat.DragonPolicy`, as in your
attached log (line 2877).

---

## 9. Annotation processor warning on `MixinLootTableAttribution` (this branch)

**File:** `mixin/vanilla/MixinLootTableAttribution.java`

**Defect.** The `@Redirect` target is log4j's `Logger.warn`, which is not a Minecraft member. The mixin
is remapped, so the annotation processor looked for an obfuscation mapping, found none, and warned
`Unable to locate method mapping for @At(INVOKE.<target>) 'Lorg/apache/logging/log4j/Logger;warn(Ljava/lang/String;)V'`.
The refmap had no entry for it, so runtime behaviour was already correct.

**Fix.** `remap = false` on that `@At` only.

**Reproduce.** `.\gradlew.bat clean build` on `main` (before this branch) prints the warning.

**Expected (this branch).** No warning.

**Regression.** The redirect still applies:
1. Run `/setblock <B> minecraft:hopper`.
2. Repeat these two commands a few times:
   `/blockdata <B> {LootTable:"minecraft:chests/simple_dungeon"}` and
   `/replaceitem block <B> slot.container.0 minecraft:air`.
3. When a roll yields more than 5 distinct stacks, the log shows
   `[Medieval Times Mixins]: Tried to over-fill a container -- table minecraft:chests/simple_dungeon | container net.minecraft.tileentity.TileEntityHopper size=5 alreadyUsed=0 | called from ...`,
   rather than vanilla's bare line.

---

## 10. Offline check (no server)

```powershell
powershell -ExecutionPolicy Bypass -File tools\mixin-harness\run.ps1
```

This needs a JDK 11 or newer; the script finds one or takes `-Jdk <path>`. It also needs git and Maven
Central on the first run, and takes about 20 seconds.

It compiles the mixins from `96cc13c` and from the working tree, and applies each build with the real
Mixin 0.8.7 transformer and MixinExtras 0.5.5 (the version MixinBooter 11.16 loads on your server) to
stand-in classes that copy the method shapes of the real targets. Each fix gets a test. The final table
must show every bug check `before FAIL / after PASS` and every regression check `PASS / PASS`, ending
with `RESULT: every bug reproduces on the baseline, is fixed on the working tree, and nothing regressed.`
The exit code is 0 only in that case. Fixes 7–9 are not covered; they need no harness.
