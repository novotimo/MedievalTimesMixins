# Bug fix report

B18, B15, B2b and B13 are confirmed fixed in game and are no longer listed. The fixes below were merged
in PR #1 (baseline `96cc13c`), except the annotation-processor fix (section 5). That fix,
`tools\build-jars.ps1` and the PowerShell harness are on branch `claude/tender-edison-ygomlf`. Check
that branch out before 0.1.

Each section has numbered in-game steps. **Reproduce** runs them on the baseline jar (the bug shows).
**Expected** runs them on the fixed jar (the bug is gone). **Regression** gives the same result on both
jars. Section 6 runs the same before/after check offline.

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
  - You need to be an op (the dedicated-server default level, 4, covers every command here).
  - `config\mixinbooter.cfg` must not blacklist any `mixins.mtmixins.*.json`.
- **Creating files.** Use Notepad (UTF-8), or the `Set-Content -Encoding Ascii` commands given. Never
  use `>` or `Out-File` in Windows PowerShell 5.1. They write UTF-16, which these mods cannot read, and
  some of them fail silently.
- **Commands.** Type every command in chat. `/gamemode` on this server is ForgeEssentials'. It accepts
  the numbers 0, 1 and 3.
- **Relative coordinates.** Some steps use `~` positions in several commands in a row. Do not move
  between those commands.
- **Command blocks (section 2).** Set `enable-command-block=true` in `server.properties` before
  starting. In game, run `/gamerule commandBlockOutput false` and `/gamerule logAdminCommands false`.
  Without these, every command a block runs is echoed to chat and to the log. The blocks are placed with
  `/setblock`, so you need no GUI and no creative mode.

---

## 1. lootattrib: the shared empty loot table's name grew on every lookup

**File:** `mixin/vanilla/MixinLootTableManagerName.java`

**Defect.**
- Every missing loot table resolves to the single `LootTable.EMPTY_LOOT_TABLE`.
- Once it had been looked up under two names, every later lookup copied its whole name and appended
  about 33 characters.
- So each lookup of a missing table cost more than the one before, until a restart.

**Fix.** The table is marked shared once and then left alone. A lookup costs the same however many came
before it.

**How the test works.**
- It rolls 2,000 loot tables in a single tick and times that tick with spark.
  - A *round* rolls the missing table `mtmixins:missing_a`.
  - A *control* rolls vanilla's real, empty table `minecraft:empty`. That is the same work per
    container but a different table, which nothing ever renames.
  - On the baseline the rounds climb far above the controls. On the fixed jar they stay level with
    them.
- No mobs and no items are involved. An empty table produces nothing, and the only thing that runs
  every tick is a field of idle hoppers.
- How a tick rolls 2,000 tables:
  - A hopper looks into the container above it every tick.
  - Looking into a dispenser rolls its loot table first.
  - So a layer of 2,000 dispensers on 2,000 hoppers rolls 2,000 tables in the tick the layer gets them.
  - Each `/fill` re-arms the layer by flipping the dispensers between facing down (`0`) and facing up
    (`1`). Lootr's default config never converts dispensers, so it stays out of the way.

**Setup (once, for both jars).** Pick flat, unclaimed ground that already exists, at least 45 × 55
blocks, with nothing on it you want to keep: the commands replace blocks there. Stand at the north-west
corner; the area runs east and south of you.

Every command in this section is relative to where you stand, so stay on this spot until both jars are
done. When you restart the server, log out without moving: you rejoin where you logged out. Run:
1. `/fill ~3 ~ ~3 ~42 ~ ~52 minecraft:hopper`. It reports `2000 blocks filled`.
2. `/fill ~3 ~1 ~3 ~42 ~1 ~52 minecraft:dispenser 0`. It reports `2000 blocks filled`. The dispensers
   sit on the hoppers, facing down, with no loot table yet.

**Reproduce (baseline jar, freshly started server).** Without moving:
1. Run `/save-off`. It replies `Turned off world auto-saving`. Otherwise spark reports the 45-second
   autosave as if it were a round. Stopping the server still saves everything.
2. Run `/spark tickmonitor --threshold-tick 100 --without-gc`. Wait for `Analysis is now complete`
   (about 6 seconds), then watch for 60 seconds.
   - From then on it prints `Tick #... lasted N ms` for every tick over 100 ms. Spark counts from one
     tick's start to the next, so an idle tick reads about 50 ms.
   - If `lasted` lines appear while you do nothing, run `/spark tickmonitor` to stop it and start it
     again with `--threshold-tick 150`. Do not go higher, or the first baseline rounds will not show.
3. *Opening control.* Run `/fill ~3 ~1 ~3 ~42 ~1 ~52 minecraft:dispenser 1 replace {LootTable:"minecraft:empty"}`.
   Wait 5 seconds, then run `/fill ~3 ~1 ~3 ~42 ~1 ~52 minecraft:dispenser 0 replace {LootTable:"minecraft:empty"}`.
   - The line that belongs to a `/fill` is the one printed right after its `2000 blocks filled`. For a
     control it is usually no line at all, or a short one.
   - The log must never show `Couldn't find resource table minecraft:empty`.
4. *Mark the empty table shared.* Run
   `/setblock ~ ~ ~2 minecraft:dispenser 0 replace {LootTable:"mtmixins:missing_b"}`, then
   `/replaceitem block ~ ~ ~2 slot.container.0 minecraft:air`.
   - The log must show `Couldn't find resource table mtmixins:missing_b`.
   - Without it, the rounds below all use one name and the baseline never grows.
5. *Rounds.* Run these two commands alternately, 6 in total, about 5 seconds apart:
   ```
   /fill ~3 ~1 ~3 ~42 ~1 ~52 minecraft:dispenser 1 replace {LootTable:"mtmixins:missing_a"}
   /fill ~3 ~1 ~3 ~42 ~1 ~52 minecraft:dispenser 0 replace {LootTable:"mtmixins:missing_a"}
   ```
   - The log shows `Couldn't find resource table mtmixins:missing_a` once.
   - If a `/fill` says `No blocks filled`, you ran the same facing twice in a row. Run the other one.
   - After the first round, run `/blockdata ~42 ~1 ~52 {}`. It changes nothing and replies
     `The data tag did not change: {...}` with that dispenser's data, which should not contain
     `LootTable`. If it does, the hoppers are not looking into the dispensers every tick. The test
     still works one round late, because each `/fill` rolls the previous round's tables as it empties
     the dispensers. Run one extra round and read the trend from round 2.
6. *Closing control.* Run the two commands of step 3 again.
7. Run `/spark tickmonitor` (it stops the monitor), `/setblock ~ ~ ~2 minecraft:air` and `/save-on`.
   Restart the server before running the other jar. The name lives until a restart.

**What the baseline shows.**
- The rounds' lines are far above the opening control, and they trend upward. A single round can come
  out shorter than the one before it.
- For scale, the baseline's own bytecode replayed on Java 8 with Aikar's flags:

  | Round | 1 | 2 | 3 | 4 | 5 | 6 |
  |---|---|---|---|---|---|---|
  | Extra time per round | 0.11 s | 0.32 s | 0.37 s | 0.57 s | 0.71 s | 0.86 s |

  Your machine will differ; judge the trend and the gap to the controls.
- The closing control drops back to the opening control's level: only missing-table lookups got slower.
- Tick times between rounds stay normal, because the cost is paid per lookup.

**Expected (fixed jar, freshly started server).** Same steps. All six rounds are about the same as the
controls, with no upward trend. On most machines the monitor prints nothing for any of them. Round 1
can be the slowest: that is the JIT warming up.

**Clean up (after both jars).** From the same spot, run `/fill ~3 ~ ~3 ~42 ~1 ~52 minecraft:air`.

**Regression (both jars): real tables are still named.** Section 5's regression shows
`table minecraft:chests/simple_dungeon`.

---

## 2. B20: the "% of capacity" in the loot-fill log used the wrong container

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

## 3. The `@Mod` version said 0.1.0 in the 0.2.0 build

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

## 4. DragonBridge's fail-closed message said "inside claims"

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

Also check `Dragon Griefing` in `config\ice_and_fire.cfg` (category `all`). In the server folder,
`Select-String -Path config\ice_and_fire.cfg -Pattern 'Dragon Griefing'` prints
`I:"Dragon Griefing"=N`. N must be 0 or 1. With 2, dragons never change blocks on any jar.

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

## 5. Annotation processor warning on `MixinLootTableAttribution`

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

## 6. Offline check (no server)

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

The version, DragonBridge and annotation-processor fixes are not covered; sections 3–5 test them
directly. The harness still contains the B18, B15, B2b and B13 checks.
