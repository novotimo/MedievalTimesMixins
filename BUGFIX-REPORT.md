# Bug fix report

Fixes 1–8 were merged in PR #1 (baseline `96cc13c`). Fix 9, `tools\build-jars.ps1` and the PowerShell
harness are on branch `claude/tender-edison-ygomlf`. Check that branch out before 0.1.

Each section has numbered in-game steps. **Reproduce** runs them on the baseline jar (the bug shows).
**Expected** runs them on the fixed jar (the bug is gone). **Regression** gives the same result on both
jars. Section 10 runs the same before/after check offline.

## 0. Setup

### 0.1 Get the branch and build both jars (PowerShell, repo root)

```powershell
git fetch origin
git switch claude/tender-edison-ygomlf
git merge --ff-only origin/claude/tender-edison-ygomlf
powershell -ExecutionPolicy Bypass -File tools\build-jars.ps1
```

This writes `..\mtmixins-BASELINE.jar` (sources at `96cc13c`) and `..\mtmixins-FIXED.jar` (current
sources). Gradle 9 needs Java 17+. If the build says otherwise, add `-Jdk "<JDK 17+ folder>"`. The
script:
- refuses to run if `src` has uncommitted changes;
- restores `src` even when a build fails;
- fails unless the baseline jar lacks `MixinMaterialSpawnLocation.class` and the fixed jar has it, so
  it cannot hand you the same build twice.

It uses `gradlew.bat`, or `gradle` from the PATH. If you have neither and only build from IntelliJ, do it
by hand:
1. `git status --short src` must print nothing.
2. `git restore --source 96cc13c --worktree -- src`
3. IntelliJ Gradle tool window → MedievalTimesMixins → Tasks → build: run `clean`, then `build`.
4. `Copy-Item build\libs\mtmixins-0.2.0.jar ..\mtmixins-BASELINE.jar`
5. `git restore --worktree -- src`
6. Run `clean`, then `build` again. Then `Copy-Item build\libs\mtmixins-0.2.0.jar ..\mtmixins-FIXED.jar`
7. `git status --short src` must print nothing. Then
   `tar -tf ..\mtmixins-BASELINE.jar | Select-String MixinMaterialSpawnLocation` must print nothing,
   and the same command on `..\mtmixins-FIXED.jar` must print one line.

### 0.2 Test server

- **Server and jars.** Use a copy of the server folder, not production. To switch builds: stop the
  server, delete every `mtmixins*.jar` from `mods\`, copy in exactly one of the two jars, start.
- **Permissions and config.**
  - You need op level 4 (`/forge gen` requires it; it is the dedicated-server default).
  - `config\mixinbooter.cfg` must not blacklist any `mixins.mtmixins.*.json`.
  - Delete `config\mtmixins-collisions.properties` unless a step says otherwise.
- **Creating files.** Use Notepad (UTF-8), or the `Set-Content -Encoding Ascii` commands given. Never
  use `>` or `Out-File` in Windows PowerShell 5.1. They write UTF-16, which these mods cannot read, and
  some of them fail silently.
- **ForgeEssentials commands.** On this server `/tp`, `/gamemode`, `/kill` and `/time` are
  ForgeEssentials commands. The steps only use:
  - `/gamemode 0|1|3`;
  - `/tp <x> <y> <z>`, which stays in your current dimension;
  - `/forge setdim @p <dim> <x> <y> <z>` to change dimension. It refuses if you are already in that
    dimension; use `/tp` then.

  Your spawn is ForgeEssentials world 10, not the Overworld (0). Type every command in chat.
- **Relative coordinates.** Some steps use `~` positions in several commands in a row. Do not move
  between those commands.
- **Command blocks (sections 5 and 6).** Set `enable-command-block=true` in `server.properties` before
  starting. In game, run `/gamerule commandBlockOutput false` and `/gamerule logAdminCommands false`.
  Without these, every command a block runs is echoed to chat and to the log. The blocks are placed with
  `/setblock`, so you need no GUI and no creative mode.

---

## 1. B18: a minecart stops being pushable after 8 pushes

**Files:** `mixin/vanilla/MixinEntityCollisionCount.java`, `mixin/vanilla/MixinEntityLivingBaseCollisions.java`

**Defect.**
- The per-tick push counter was reset at the head of `Entity.onUpdate`. `EntityMinecart.onUpdate`
  never calls `super.onUpdate()`, so a minecart's counter only ever went up.
- A push is refused when *either* entity is at the cap. After 8 pushes the cart therefore refused every
  push from a player or mob until its chunk unloaded.
- A stationary cart only collides with other carts in its own tick, so nothing else moved it either.

**Fix.** The counter is stamped with `world.getTotalWorldTime()` and reads as 0 in any other tick.

**Reproduce (baseline jar).**
1. Go to the Overworld, at least 300 blocks from world spawn on X or Z (spawn chunks never unload), on
   flat open ground. Be in survival or creative, not spectator.
2. Run `/save-on`, then `/summon minecraft:minecart ~3 ~ ~`.
3. Walk into the cart, back off, and repeat.
   - For the first one or two contacts it slides (8 ticks of contact in total, under half a second).
   - After that it does not react to being walked into, from any side.
4. Walk more than (view-distance + 2) × 16 blocks away, wait 5 seconds, and come back. The chunk
   unloaded and reloaded, so the cart moves for another 8 ticks of contact, then freezes again. A
   restart does the same.

**Expected (fixed jar).** The cart slides on every contact, indefinitely. That is what you saw; your log
is the 0.2.0 fixed build.

**Regression (same result on both jars).** Each step is on open flat ground.
1. Run `/summon minecraft:cow ~2 ~ ~` five times without moving. The cows push apart within a second.
   Walking into one shoves it. The log has
   `No mtmixins-collisions.properties found, so entity pushing is capped at Spigot's default of 8 ...`.
2. Stop the server, and in the server folder run:
   `Set-Content -Path config\mtmixins-collisions.properties -Value 'max-entity-collisions = 0' -Encoding Ascii`.
   Start the server and repeat step 1.
   - The cows stay stacked on one spot until they wander off.
   - You walk through a cow without moving it.
   - The log has `Entity pushing capped at 0 per entity per tick, from mtmixins-collisions.properties`.
     It is written at the first collision, not at startup.
   - If the log instead says any of these, stop the server, `cd` to the server folder, and recreate the
     file with the command above:
     - `No mtmixins-collisions.properties found`: the file is not in `<server folder>\config\`.
     - `Entity pushing capped at 8`: the file was found but the key was not read (wrong encoding).
     - `Could not read mtmixins-collisions.properties`.
3. Do the same with `-Value 'max-entity-collisions = -1'`. Pushing is vanilla (cows push apart, walking
   shoves them), and the log says `capped at -1`.
4. Delete the file and restart. The result is the same as step 1.

---

## 2. B15: a chunk-unload race let duplicate entities through

**File:** `mixin/vanilla/MixinWorldServerEntityDupe.java`

**Defect.**
- An incoming entity's UUID could belong to a world entity that was queued for unload. In that case the
  loop hit `continue` before checking the other copies in the same 16-block slice, so all copies went
  to vanilla.
- Vanilla swapped in the first copy and refused the rest with `Keeping entity ... that already exists`.
  That leaves the refused copies in the chunk's entity list, so they are saved again.
- They survive until the chunk's next load that is not itself a race. That load deletes them.

**Fix.** Same order as Paper's resolver: a queued-for-unload world copy counts as absent, and the copies
seen earlier in the batch are checked next.

**Why the steps work.**
- A race needs an entity to still be queued for unload when a chunk holding a copy of it loads.
  Normally the queue is emptied at the end of the same tick.
- Forge's `WorldServer.updateEntities` returns early once a world has had no players for 300 ticks (and
  has no force-loaded chunks), so the queue is not emptied.
- With nobody in the Overworld, an entity unloaded there stays queued. `/forge gen` can then load the
  second chunk while it is still queued.

**Coordinates.**
- The steps use x=2008, which puts the stands in chunks (125, 0) and (125, 1), region file `r.3.0.mca`,
  NBTExplorer `Chunk [29, 0]` and `Chunk [29, 1]`.
- Check the Overworld spawn: in NBTExplorer, `world\level.dat` → Data → `SpawnX`/`SpawnZ`. If that is
  within 400 blocks of (2008, 16), use x=4008 everywhere instead. That gives chunk 250, file `r.7.0.mca`,
  NBTExplorer `Chunk [26, 0]` / `Chunk [26, 1]`, and log lines `at 4008,80,24`.
- `world` means the folder named by `level-name` in `server.properties`. A new world
  (`level-name=b15test`) is simplest. On a copy of the live world, chunk loaders such as FTB Utilities'
  can keep Overworld chunks ticking, and then no race happens.

**One-time setup: make a prepared world.**
1. Join, then run `/gamemode 3` and `/forge setdim @p 0 2008 100 16` (if you are already in the
   Overworld: `/tp 2008 100 16`). Wait until the terrain has loaded.
2. Run:
   ```
   /summon minecraft:armor_stand 2008 80 8 {CustomName:"E1",NoGravity:1b}
   /summon minecraft:armor_stand 2008 80 24 {CustomName:"E2",NoGravity:1b}
   /summon minecraft:armor_stand 2008 80 24 {CustomName:"E3",NoGravity:1b}
   ```
   E1 is in chunk A (125, 0). E2 and E3 are in chunk B (125, 1), at the same position and in the same
   16-block slice.
3. Run `/forge setdim @p -1 0 100 0`. You are now in the Nether in spectator mode, and you will rejoin
   there.
4. Run `/save-all`, wait 5 seconds, then `/stop` (or type `stop` in the server console). Wait until the
   server has fully stopped before opening NBTExplorer.
5. In NBTExplorer, open `world\region\r.3.0.mca`.
   - Chunk [29, 0] → Level → Entities: set E1's `UUIDMost` to `7` and `UUIDLeast` to `7`.
   - Chunk [29, 1] → Level → Entities: do the same for E2 and E3.
   - Save.
6. Rename the folder to `B15-prepared` and never start a server on it. **Before every run below**
   (six runs: the main test and two regressions, once per jar), delete `world` and copy `B15-prepared`
   to `world`. A run changes the world: it saves the race result, and the fixed jar deletes E3.

**Reproduce (baseline jar, fresh copy).**
1. Start the server and join. You are in the Nether, so the Overworld has no players.
2. Wait 60 seconds.
3. Run `/forge gen 2008 80 8 1 0`. It replies `Finished generating 0 new chunks (out of 1) for dimension 0.`
   That is correct: the chunk exists, and the command still loads it and queues it for unload. Do not
   run it again.
4. Run `/forge gen 2008 80 24 1 0` (same reply). Chunk B loads while E1 is still queued.
5. `logs\latest.log` now shows:
   ```
   [Medieval Times Mixins]: Chunk unload race, not a duplicate: minecraft:armor_stand (UUID 00000000-0000-0007-0000-000000000007) at 2008,80,24 ...
   [net.minecraft.world.WorldServer]: Keeping entity minecraft:armor_stand that already exists with UUID 00000000-0000-0007-0000-000000000007
   ```
6. Run `/save-all flush`, then `/stop`, and wait for the server to stop. In NBTExplorer, Chunk [29, 1] → Level → Entities still holds two
   armor stands with UUID 7/7.

**Expected (fixed jar, fresh copy, same steps).**
- Step 5 shows the race line, then
  `Duplicate UUID resolved: dropped a second copy of minecraft:armor_stand (UUID 00000000-0000-0007-0000-000000000007) at 2008,80,24 in overworld. ...`
  and vanilla's `Tried to add entity minecraft:armor_stand but it was marked as removed already`.
- There is no `Keeping entity` line.
- Step 6 shows one armor stand in Chunk [29, 1].

**If step 5 shows no race line on either jar.** Only the first race of a session is logged. Check these
in order, then retry on a fresh copy:
1. You are the only player online.
2. `/forge tps` shows the Overworld at 20 TPS. If it is lower, wait longer at step 2.
3. Chunks (125, 0) and (125, 1) hold no chests, hoppers or machines. If they do, pick another x.
4. If all of that holds, something force-loads Overworld chunks (an FTB Utilities chunk loader, for
   example). Repeat on a new world.

**Regression (same result on both jars, fresh copy each).**
1. *Plain race.* After copying, delete E3 from Chunk [29, 1] in NBTExplorer, then do steps 1–6.
   - Step 5 shows only the race line: no drop, no `Keeping entity`.
   - Step 6 shows one armor stand.
2. *Duplicates without a race.*
   - After joining, run `/forge setdim @p 0 0 100 0` and wait 10 seconds. A player in the Overworld
     keeps entity updates running there.
   - Run steps 3–4.
   - The log shows
     `Duplicate UUID resolved: dropped a second copy of minecraft:armor_stand ... at 2008,80,24 in overworld`
     and no race line for UUID `00000000-0000-0007-0000-000000000007`.
   - When you arrive in the Overworld, a `Chunk unload race` line for some other entity near the
     Overworld spawn may appear. An autosave unloaded those spawn-area chunks while the Overworld was
     empty; they are not part of this test.

---

## 3. B2b: OreVeins used another world's biome

**Files:** `util/ChunkBiomeCache.java`, `mixin/oreveins/MixinWorldGenVeins.java`

**Defect.** `WorldGenVeins` is a single instance shared by every dimension. Its biome cache was keyed
by chunk coordinates only. So generating chunk (X, Z) in one dimension right after the same (X, Z) in
another reused the first dimension's biomes.

**Fix.** Cache entries are stamped with the world that wrote them, held weakly so an unloaded world can
still be freed. A lookup from any other world misses.

**Setup (both jars).**
1. In the server folder run:
   ```powershell
   Set-Content -Encoding Ascii -Path config\oreveins\mtmixins_repro.json -Value '{"mtmixins_repro_nether_only": {"type": "cluster", "ore": "minecraft:sponge", "stone": ["minecraft:stone", "minecraft:netherrack", "minecraft:soul_sand", "biomesoplenty:flesh"], "count": 6, "rarity": 1, "min_y": 32, "max_y": 64, "horizontal_size": 16, "vertical_size": 10, "density": 80, "dimensions": [0, -1], "biomes": ["NETHER"]}}'
   ```
   - The vein is allowed in the Overworld and the Nether, but only in biomes tagged `NETHER`.
   - The Overworld has none, so the vein can never legitimately place a block there.
   - Dry sponge (meta 0) does not generate naturally.
   - The extra stones cover Biomes O' Plenty Nether biomes that have little netherrack.
2. Restart. `logs\latest.log` must say `[oreveins]: Registered 75 Veins Successfully.` (yours said 74
   before), with no `Unable to open the file` and no error naming `mtmixins_repro_nether_only`. If it
   still says 74, stop: the test cannot show anything.
3. Have no other players online.
4. If a world border is configured (the worldborder mod, or `/feworldborder`, which is set separately
   for each world), check it in **both the Overworld (0) and the Nether (-1)**.
   - x/z 40000–40031, 48000–48031, 56000–56031 and 64000–64031 must be inside it.
   - Replace any area that is not with an unexplored multiple of 16 that is inside, in both dimensions.
   - Below, for each area base N (40000, 48000, 56000, 64000), replace N with its new value, and
     N+8 / N+16 / N+23 to match.

**Reproduce (baseline jar).**
1. Run `/forge gen 40000 64 40000 1 -1`. This generates and populates Nether chunk (2500, 2500).
2. Straight after, run `/forge gen 40000 64 40000 1 0`. This does the same in the Overworld.
   - Each must reply `Finished generating 1 new chunks (out of 1)`.
   - `0 new chunks` means the area was not fresh. Pick another area.
3. Run `/gamemode 3`, then `/forge setdim @p 0 40016 100 40016` (if you are already in the Overworld:
   `/tp 40016 100 40016`).
4. Run `/fill 40008 32 40008 40023 64 40023 minecraft:air 0 replace minecraft:sponge 0`.
   - It reports **N blocks filled, with N > 0**: sponge in Overworld stone, placed by a vein that
     requires a Nether biome.
   - If it says `No blocks filled`, something else generated chunks between steps 1 and 2. Repeat at
     another fresh area before concluding anything: 56000 / 56008 / 56016 / 56023, then 64000 / 64008 /
     64016 / 64023, in the same places as 40000 / 40008 / 40016 / 40023.

**Expected (fixed jar, a different fresh area).**
```
/forge gen 48000 64 48000 1 -1
/forge gen 48000 64 48000 1 0
/gamemode 3
/forge setdim @p 0 48016 100 48016
/fill 48008 32 48008 48023 64 48023 minecraft:air 0 replace minecraft:sponge 0
```
The last command reports **No blocks filled**.

**Regression (same result on both jars).**
1. Run `/forge setdim @p -1 40016 100 40016` (on the fixed jar: `48016 100 48016`). Then run the same
   `/fill` as above in the Nether. N > 0, so the vein still generates where it should.
2. Delete `config\oreveins\mtmixins_repro.json` when you are done.

---

## 4. B13: Lycanites `block` and `material` spawners still loaded or generated chunks

**Files:** `mixin/lycanites/MixinBlockSpawnLocation.java`, `mixin/lycanites/MixinMaterialSpawnLocation.java` (new), `mixins.mtmixins.cascade.json`

**Defect.**
- `BlockSpawnLocation.getSpawnPositions` calls `world.getBlockState()` on every candidate *before*
  `isValidBlock`, so the guard on `isValidBlock` never saw an unloaded chunk.
- `MaterialSpawnLocation` overrides `isValidBlock` without calling `super`.
- Only `random` locations were covered. The stock `lava`, `fire` and `mineshaft` spawners are `block`
  locations.

**Fix.** Inside the sweep, `getBlockState` returns air for an unloaded position, and
`MaterialSpawnLocation.isValidBlock` gets the same guard.

**Setup (both jars).**
1. In `server.properties` set `view-distance=3` (restore it afterwards). The server then only loads
   chunks within 3 chunks of a player.
2. In the server folder run:
   ```powershell
   $d = "config\lycanitesmobs\spawners"
   Set-Content -Encoding Ascii -Path "$d\mtrepro_block.json" -Value '{"name": "mtrepro_block", "type": "spawner", "enabled": true, "enableWithoutMobs": true, "ignoreBiomes": true, "conditions": [], "triggers": [], "locations": [{"type": "block", "rangeMin": [0, 0, 0], "rangeMax": [80, 0, 80], "blocks": [], "listType": "blacklist"}]}'
   Set-Content -Encoding Ascii -Path "$d\mtrepro_material.json" -Value '{"name": "mtrepro_material", "type": "spawner", "enabled": true, "enableWithoutMobs": true, "ignoreBiomes": true, "conditions": [], "triggers": [], "locations": [{"type": "material", "rangeMin": [0, 0, 0], "rangeMax": [80, 0, 80], "materials": ["air"]}]}'
   Set-Content -Encoding Ascii -Path "$d\mtrepro_random.json" -Value '{"name": "mtrepro_random", "type": "spawner", "enabled": true, "enableWithoutMobs": true, "ignoreBiomes": true, "conditions": [], "triggers": [], "locations": [{"type": "random", "rangeMin": [0, 0, 0], "rangeMax": [96, 2, 96], "limit": 3000, "easyDifficultyRangeScale": 1, "normalDifficultyRangeScale": 1, "hardDifficultyRangeScale": 1}]}'
   ```
   - The spawners have no triggers and no mobs, so they only run when you use `/lm spawner test`.
   - A `block` or `material` sweep reads every position within 80 blocks horizontally, at your Y level.
   - The random one tries 3000 random columns within 96 blocks.
3. Preconditions, otherwise the spawner returns before it sweeps and both jars look fixed:
   - `/gamerule doMobSpawning` prints true.
   - In `config\lycanitesmobs\spawning.cfg`, `Disable Spawning` is false.
   - `config\lycanitesmobs\globalspawner.json`, if present, has `"conditions": []`.
4. Restart. Type `/lm spawner test mtrepro` and press Tab: all three names must be offered.

**Reproduce (baseline jar).**
1. Go to the Overworld at least 300 blocks from world spawn (spawn chunks are always loaded), into
   terrain that already exists. Stand still until chunks stop loading.
2. Run `/save-all` and wait 2 seconds. This unloads everything outside your view distance that something
   else had loaded.
3. Run `/testforblock ~80 ~ ~ minecraft:stone`. It reports **"Cannot test for block outside of the world"**.
   That spot is always exactly 5 chunks away and is the last column the sweep reads. If it names a
   block instead, walk another 300 blocks and repeat from step 2.
4. Run `/save-off`. While saving is off the server neither autosaves nor unloads chunks, so whatever
   the next step loads stays loaded until you check it. Otherwise the 45-second autosave can unload it
   first.
5. Run `/lm spawner test mtrepro_block`. The server may freeze for a few seconds while it loads or
   generates about 70 chunks.
6. Run `/testforblock ~80 ~ ~ minecraft:stone` again. It now names the block there (`The block at ...
   is ...` or `Successfully found the block at ...`): the sweep loaded the chunk, or generated it.
7. Run `/save-on`.
8. Walk 300 blocks further and repeat steps 2–7 with `/lm spawner test mtrepro_material`. Same result.

**Expected (fixed jar, new spots).** Same steps. Step 6 still says **"Cannot test for block outside of
the world"** for both spawners, and there is no freeze.

**Regression (same result on both jars).**
1. *Random path.* Walk 300 blocks further and do steps 2–4. Then run `/lm spawner test mtrepro_random`,
   then steps 6–7.
   - Step 6 still says "outside of the world" on *both* jars.
   - About 20 of the 3000 columns fall in that chunk, and the `isValidBlock` guard (already present at
     baseline) keeps them from loading it.
2. *Loaded positions still found.*
   - Set `/difficulty normal`. On Peaceful, Lycanites rejects every non-peaceful mob, so only the
     cephignis could spawn.
   - Stand at a lava lake or the Nether lava sea, with at least 8 still lava source blocks within 32
     blocks.
   - Run `/lm spawner test lava`, up to 5 times. Lycanites lava mobs (cephignis, salamander, ...) spawn
     in the lava on both jars.
3. Delete the three `mtrepro_*.json` files and restore `view-distance`.

---

## 5. lootattrib: the shared empty loot table's name grew on every lookup

**File:** `mixin/vanilla/MixinLootTableManagerName.java`

**Defect.**
- Every missing loot table resolves to the single `LootTable.EMPTY_LOOT_TABLE`.
- Once it had been looked up under two names, every later lookup appended about 33 characters to its
  name.
- The string grows without bound and each append copies all of it, so tick time climbs steadily.

**Fix.** The table is marked shared once and then left alone.

`/kill` on this server is ForgeEssentials' and only kills players. So the zombies below are given
an instant-health effect (which damages undead), and they die on their first tick. Every death looks up
the zombie's loot table.

**Reproduce (baseline jar).** Use flat, unclaimed Overworld ground, with section 0.2's command-block
settings done and `/gamerule doMobLoot` true.
1. Run `/summon minecraft:zombie ~ ~ ~3 {DeathLootTable:"mtmixins:missing_b",ActiveEffects:[{Id:6b,Amplifier:5b,Duration:1}]}`.
   - The zombie dies at once.
   - This names the empty table `mtmixins:missing_b`. In step 3, the first lookup of
     `mtmixins:missing_a` marks it shared. From then on the baseline appends to the name on every lookup.
2. Without moving, run this **20 times**:
   `/summon minecraft:armor_stand ~ ~3 ~6 {CustomName:"lr",NoGravity:1b,Invisible:1b,Marker:1b}`
3. Without moving, run
   `/setblock ~2 ~ ~2 repeating_command_block 5 replace {auto:1b,Command:"execute @e[name=lr] ~ ~ ~ summon zombie ~ ~ ~ {DeathLootTable:\"mtmixins:missing_a\",NoAI:1b,Silent:1b,ActiveEffects:[{Id:6b,Amplifier:5b,Duration:1}]}"}`.
   - It starts at once: 20 zombies spawn and die every tick, which is 20 lookups of a missing table per
     tick.
   - The log shows `Couldn't find resource table mtmixins:missing_a` once. That confirms the lookups
     are happening.
4. Run `/spark tps` (or `/forge tps`) right away, then every 2 minutes for 10 minutes.
   - The tick time (MSPT) is higher at every check and does not level off. Within roughly 3–10 minutes
     the server falls behind ("Can't keep up!").
   - The constant cost of 20 zombies a tick is the same on both jars. Only the climb is the bug.
5. Stop it from the same spot with `/setblock ~2 ~ ~2 minecraft:air`. Restart the server: the name never
   shrinks, so the baseline stays slow until a restart.

**Expected (fixed jar).** Same steps: MSPT stays flat for the whole 10 minutes. On the same world, skip
step 2. The 20 `lr` armor stands from the first run are still there, and summoning 20 more would make
40 zombies a tick.

**Regression (both jars): real tables are still named.** Section 9's regression shows
`table minecraft:chests/simple_dungeon`.

---

## 6. B20: the "% of capacity" in the loot-fill log used the wrong container

**File:** `mixin/vanilla/MixinLootTableShuffle.java`

**Defect.** Every 100th fill, the log line `Loot fill: ... Average occupancy over N fills: X slots,
Y% of capacity` divided the running average by **that one container's** slot count. With mixed
container sizes Y is meaningless and can exceed 100%.

**Fix.** Slots used ÷ slots offered, both summed over all fills. The line also prints the "every slot
used" counter, which used to be collected but never shown.

**Reproduce (baseline jar).**
1. Start the server and join. The fill counter starts at 0 at every start. Stay in terrain that
   already exists, and do not open or break any loot container.
2. Go to a flat spot with section 0.2's command-block settings done. Without moving, run these in
   order. They place the chest and the hopper, then the three Chain blocks (idle until triggered), then
   the Repeating block, which starts at once.
   ```
   /setblock ~1 ~ ~4 minecraft:chest
   /setblock ~3 ~ ~4 minecraft:hopper
   /setblock ~2 ~ ~2 chain_command_block 5 replace {auto:1b,Command:"replaceitem block ~-1 ~ ~2 slot.container.0 minecraft:air"}
   /setblock ~3 ~ ~2 chain_command_block 5 replace {auto:1b,Command:"blockdata ~ ~ ~2 {LootTable:\"minecraft:chests/jungle_temple_dispenser\"}"}
   /setblock ~4 ~ ~2 chain_command_block 5 replace {auto:1b,Command:"replaceitem block ~-1 ~ ~2 slot.container.0 minecraft:air"}
   /setblock ~1 ~ ~2 repeating_command_block 5 replace {auto:1b,Command:"blockdata ~ ~ ~2 {LootTable:\"minecraft:chests/simple_dungeon\"}"}
   ```
   Each tick this does four things, in order:
   - `/blockdata` empties the 27-slot chest and gives it a loot table.
   - `/replaceitem` makes the chest fill.
   - The same two commands empty and fill the 5-slot hopper.

   Fills alternate, so every even-numbered fill is the hopper.
3. In the server folder, wait until `Select-String -Path logs\latest.log -Pattern 'over 200 fills'` prints a line (about 5
   seconds at 20 TPS, longer if the server lags). Then stop it from the same spot with
   `/setblock ~1 ~ ~2 minecraft:air`.
4. In the server folder, run `Select-String -Path logs\latest.log -Pattern 'over 100 fills'`. It reads
   `Loot fill: N stacks into 5 slots (target was T). Average occupancy over 100 fills: X slots, Y% of capacity. Register B20.`
   - X is about 8, and **Y = 20 × X** (about 160%) give or take 2.
   - That is the running average divided by this one hopper's 5 slots. Above 100% is impossible.

**Expected (fixed jar).** Same steps, on a fresh flat spot. Or, standing on the old spot, first clear
the old blocks with `/fill ~1 ~ ~2 ~4 ~ ~4 minecraft:air`; otherwise each `/setblock` says `The block
couldn't be placed`. The line reads:
`... into 5 slots ... over 100 fills: X slots, Y% of capacity; 0 fills used every slot. Register B20.`
- **Y = 6.25 × X** (about 50%) give or take 1.
- That is 100 fills × X slots used ÷ 1600 slots offered (50 chests × 27 + 50 hoppers × 5).

**If the line says `into 27 slots`.** An odd number of other loot fills came before your contraption,
so every hundredth fill is the chest. Restart and repeat. If it happens again, fix the order from the
same spot:
1. Stop the Repeating block as in step 3.
2. Run one hopper fill by hand: `/blockdata ~3 ~ ~4 {LootTable:"minecraft:chests/jungle_temple_dispenser"}`,
   then `/replaceitem block ~3 ~ ~4 slot.container.0 minecraft:air`.
3. Re-run the Repeating block's `/setblock` line.
4. Read the next `over N00 fills` line that says `into 5 slots`.
   - Baseline: Y = 20 × X (±2), over 100%.
   - Fixed: about 50%.

**If the line says `into 5 slots` but the fixed jar's Y is more than 1 away from 6.25 × X.** An even
number of other fills came first. The hopper is still every even-numbered fill; only the total slots
offered changed. Do not do the hand fill, because it would move every `N00` line onto the chest. The
fixed jar passes if Y is about 50% and under 100%. The baseline is not affected (Y = 20 × X).

**Regression.** Both jars run the same merge and split code, so X is about 8 on both (it varies by
about ±0.3 between runs). The `N stacks` of a single fill is random and will not match between runs.
Only Y differs.

---

## 7. The `@Mod` version said 0.1.0 in the 0.2.0 build

**File:** `MedievalTimesMixins.java`

The jar name and `mcmod.info` say 0.2.0 on both jars. Only the `@Mod` version FML tracks was wrong.

**Reproduce.** Use one test world.
1. Start the server with the fixed jar, then stop it.
2. Switch to the baseline jar and start. `logs\latest.log` shows
   `[FML.ModTracker]: This world was saved with mod mtmixins version 0.2.0 and it is now at version 0.1.0, things may not work well`.

**Expected.**
3. Switch back to the fixed jar and start. The log shows
   `... saved with mod mtmixins version 0.1.0 and it is now at version 0.2.0 ...`. That is your log's
   line 2247.

Client view: on a client, delete every `mtmixins*.jar` from the client's own `mods` folder and copy in
the same jar the server is running (switch it each time you switch the server). Under Mods → Medieval Times Mixins, the
detail pane reads `Version: 0.2.0 (0.1.0)` on the baseline and `Version: 0.2.0 (0.2.0)` on the fixed
jar. The list column shows 0.2.0 on both.

**Regression.** `acceptableRemoteVersions = "*"`, so a client on either jar joins a server on either jar.

---

## 8. DragonBridge's fail-closed message said "inside claims"

**File:** `bridge/DragonBridge.java`

**Defect.** When `DragonPolicy` exists but its methods do not resolve, dragons are refused every block
change everywhere. The message said "inside claims", which understates it. Behaviour does not change;
only the message does.

**Setup (test server only).** Use one of these:
- **a)** Build Civilizations with `DragonPolicy.blockChangeAllowed` renamed (for example to
  `blockChangeAllowedX`) and deploy it.
- **b)** Replace Civilizations with an empty `DragonPolicy` class:
  - Move the Civilizations jar out of `mods\`.
  - In the server folder, run this, with `$jdk` set to your JDK 17 folder:
  ```powershell
  $jdk = "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
  $t = Join-Path $env:TEMP "dragonpolicy-stub"
  New-Item -ItemType Directory -Force "$t\src\me\qourtenay\Civilizations\compat" | Out-Null
  Set-Content -Encoding Ascii -Path "$t\src\me\qourtenay\Civilizations\compat\DragonPolicy.java" -Value 'package me.qourtenay.Civilizations.compat; public final class DragonPolicy { }'
  & "$jdk\bin\javac.exe" --release 8 -d "$t\out" "$t\src\me\qourtenay\Civilizations\compat\DragonPolicy.java"
  & "$jdk\bin\jar.exe" cf mods\DragonPolicyStub.jar -C "$t\out" .
  ```
  - FML logs `FML has found a non-mod file DragonPolicyStub.jar ... injected into your classpath`.
  - Skip b) if another mod in the pack requires Civilizations.

Also check `Dragon Griefing` in `config\iceandfire.cfg`. It must be 0 or 1. With 2, dragons never change
blocks on any jar.

**Reproduce (baseline jar).**
1. Start the server. Nothing from DragonBridge is logged yet. It logs on the first dragon breath that
   reaches the mixin, which happens outside a claim.
2. On unclaimed land, in daytime, on Easy or harder, run these. Survival matters: untamed dragons
   ignore creative and spectator players.
   - `/gamemode 0`
   - `/effect @p minecraft:resistance 600 4`
   - `/effect @p minecraft:fire_resistance 600 0`
   - `/summon iceandfire:firedragon ~ ~ ~10 {AgeTicks:720000}`

   The dragon is 30 days old, which is stage 2. Stage 3 and older dragons break blocks by walking and
   flying through them, a path this mixin does not cover.
3. Let it breathe fire at you (hit it once if it ignores you). Where the breath lands, no block is
   charred and no fire appears.
4. `logs\latest.log` shows
   `[Medieval Times Mixins]: me.qourtenay.Civilizations.compat.DragonPolicy is present but does not have the expected methods. Dragon block protection will REFUSE all dragon block changes inside claims as a safe default ...`.
   The message says "inside claims", but step 3 happened on unclaimed land.

**Expected (fixed jar).** Same steps and same behaviour. Step 4 reads
`... does not have the expected methods. Without the policy there is no way to tell claimed land from unclaimed, so dragons will be REFUSED every block change everywhere as a safe default, and dragon damage is left alone. ...`

**Regression (same result on both jars).** Restore the normal Civilizations jar and remove the stub.
- The same dragon on unclaimed land chars blocks. This shows the setup can show block changes.
- The first such breath logs
  `Dragon claim policy resolved from me.qourtenay.Civilizations.compat.DragonPolicy; ...`, as in your
  log, line 2877.

---

## 9. Annotation processor warning on `MixinLootTableAttribution`

**File:** `mixin/vanilla/MixinLootTableAttribution.java`

**Defect.**
- The `@Redirect` target is log4j's `Logger.warn`, which is not a Minecraft member.
- The mixin is remapped, so the annotation processor looked for an obfuscation mapping, found none, and
  warned
  `Unable to locate method mapping for @At(INVOKE.<target>) 'Lorg/apache/logging/log4j/Logger;warn(Ljava/lang/String;)V'`.
- The refmap had no entry for it, so the redirect fell back to the literal name and runtime behaviour
  was already correct.

**Fix.** `remap = false` on that `@At` only.

**Reproduce.** In `tools\build-jars.ps1`'s output, the baseline build prints the warning. `main` does
too.

**Expected.** The fixed build prints no such warning.

**Regression (both jars).** The redirect still applies. On a flat spot, without moving:
1. Run `/setblock ~2 ~ ~2 minecraft:hopper`.
2. Run these two commands as a pair, repeatedly (up-arrow twice, Enter):
   `/blockdata ~2 ~ ~2 {LootTable:"minecraft:chests/simple_dungeon"}` and
   `/replaceitem block ~2 ~ ~2 slot.container.0 minecraft:air`.
3. When a roll yields more different items than the hopper has slots, the log shows
   `Loot genuinely does not fit: N distinct stacks for 5 slots after merging (was M before). ...`
   (N is 6 or more), followed by
   `[Medieval Times Mixins]: Tried to over-fill a container -- table minecraft:chests/simple_dungeon | container net.minecraft.tileentity.TileEntityHopper size=5 alreadyUsed=0 | called from ...`
   instead of vanilla's bare line.

---

## 10. Offline check (no server)

```powershell
powershell -ExecutionPolicy Bypass -File tools\mixin-harness\run.ps1
```

**Requirements.**
- A JDK 11 or newer. The script finds one or takes `-Jdk <JDK folder>`.
- git, or `-BaselineDir <folder holding the baseline's src\main\java\com\novotimo\mtmixins>`.
- Internet on the first run, to fetch 10 jars from Maven Central (pinned by SHA-1). Later runs are
  offline.
- A run takes about 20 seconds.

**What it does.**
- Compiles the mixins from `96cc13c` and from the working tree.
- Applies each build with the real Mixin 0.8.7 transformer and MixinExtras 0.5.5 (the version
  MixinBooter 11.16 loads on your server).
- The targets are stand-in classes that copy the method shapes of the real ones. Each fix gets a test.

**Pass criteria.**
- Every bug check shows `before FAIL / after PASS`.
- Every regression check shows `PASS / PASS`.
- The output ends with
  `RESULT: every bug reproduces on the baseline, is fixed on the working tree, and nothing regressed.`
- The exit code is 0 only in that case.

Fixes 7–9 are not covered; sections 7–9 test them directly.
