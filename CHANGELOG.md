# Changelog

## 0.2.2

Changes since 0.1.0.

**Ancient Warfare**
- Combat NPCs no longer pick up junk (bones, rotten flesh) and hold it forever. NPCs already holding junk no longer spam the server log.
- Raid protection keeps working now that TownyForge is Civilizations.

**Corpse**
- The Items button in death history works again, including when staff view another player's deaths.
- Death history lists deaths from every world. Deaths in Spawn used to show up only while you were in Spawn, and the rest only while you weren't.
- `/deathhistory <player>` works for offline players.
- Fixed getting disconnected ("A fatal error has occurred") when opening death history.

**Dynmap**
- MineFantasy Reforged and other modded blocks no longer lose faces on the map, and the server no longer logs about 80,000 "Invalid modellist patch" errors on every startup.

**ForgeEssentials**
- New players no longer have to relog on first join before they can see the NPCs at spawn or use their trades.
- Fixed a server crash when a player with a broken position logged out.

**Ice and Fire**
- Dragons follow claim protection. They can't break blocks in claimed land, and whether they can hurt someone is checked for each target instead of the old all-or-nothing rule.
- Fixed ghost dragons from the dragon horn: invisible, unridable copies that block building. A dragon released from a horn now gets a new ID, so a summoning crystal bound to it before it went into the horn has to be re-bound.
- Baby dragons on your shoulder are no longer deleted when you change dimension (portals, `/spawn`, `/home`, `/warp`, etc.). The dragon is set down where you were standing, so you can go back for it.
- Baby dragons on your shoulder are no longer deleted by a server restart or crash.
- Pressing shift to drop a shoulder dragon now drops it beside you instead of back where you picked it up.

**JJ Coats of Arms**
- The update checker no longer downloads the same file from GitHub twice a second for as long as the game is open.

**Lycanites Mobs**
- Mob spawning no longer generates new chunks, which was a major cause of worldgen lag.

**MineFantasy Reforged**
- Ancient Forge placement no longer generates new chunks (worldgen lag).

**OreVeins**
- Fixed veins sometimes using another dimension's biomes when deciding where to generate.

**Vanilla**
- Fixed duplicate entities, which showed up as pets or dragons vanishing on restart and herds disappearing after lag. Copies of the same mob are merged. Different mobs that ended up sharing an ID are both kept.
- Fixed the lockout where nobody could join until the server restarted.
- Fixed chunks sometimes failing to save ("Failed to save chunk"), which rolled builds and chest contents back to the last good save.
- Fixed loot chests sometimes spawning with items missing ("Tried to over-fill a container").
- Each entity now pushes at most 8 others per tick (the same limit as Spigot and Paper), which cuts lag in crowded pens and farms.
- Fixed a kick loop ("Invalid move player packet received") caused by certain knockback. A player already stuck in it gets moved to spawn on login, so their playerdata no longer has to be deleted.

## 0.1.0

Initial commit. Adds mixins for:

- AncientWarfare (server compat)
- ArmourersWorkshop (performance enhancement)
- BetterCaves (mineshaft fix)
- Holograms (server compat)
- InfinityLib (fix rendering crash)
- MineFantasy Reforged (fix stoves causing players to not be able to connect)
- OreVeins (performance enhancement to worldgen)
- Vanilla (performance enhancement when lots of items exist)
- Votifier (bug fixes)
