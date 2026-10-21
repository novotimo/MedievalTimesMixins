# Medieval Times Mixins 0.2.4

Changes since 0.2.2. Bug fixes only: no new items, blocks, commands or config options, and no new
required dependencies. Each fix is a Mixin patch to another mod's existing code. The jar contains only
this mod's own classes, mixin configs and metadata, and no code or assets from the mods it patches.

Each group of fixes is in its own mixin config file, named below. A server or player can switch off
any one group in `config/mixinbooter.cfg` without removing the mod.

## Just Enough Items + Horse Power: game crashes while loading (client)

- With JEI 4.16.5, the game crashed during startup ("Caught exception from Just Enough Items", a
  `NullPointerException` in Horse Power's `PressRecipeWrapper.hashCode`) whenever Horse Power had a
  press recipe that outputs a fluid. Horse Power's default config includes one: 8 leaves into
  1000 mB of water.
- Cause: JEI 4.16.5 now removes duplicate recipes from its lists, which uses each recipe's equality
  check. Horse Power 2.6.4.74's check for press recipes does not handle fluid recipes.
- Fix: Horse Power's press recipes get a correct equality check. A recipe listed twice is shown once,
  different recipes are never merged, and the fluid press recipes appear in JEI again.
- Config file: `mixins.mtmixins.horsepowerjei.json`

## Corpse 1.12.2-1.0.8: bodies under bedrock, sliding away or going through portals (server)

- A player who died below the bottom of the world got a body at y = 0, inside the bedrock floor (or
  in the End's void). Bodies below y = 1, including ones already stuck in bedrock, are now moved to
  the nearest floor close by, or to the floor at the dimension's spawn if there is none. Each move is
  logged with the old and new position.
- Bodies no longer slide away after an explosion or in flowing water.
- Bodies no longer travel through nether or end portals.
- New log lines record when another mod cancelled or emptied a player's death drops before Corpse saw
  them, and when the world refused to spawn a body, so a missing body can be traced.
- Config file: `mixins.mtmixins.corpsebody.json`

## Corpse 1.12.2-1.0.8: loot window (server, plus a cosmetic client part)

- A body no longer deletes itself while someone has its loot window open. It is removed once the last
  window closes.
- The loot window now closes when the body is gone or out of reach, or when the player looting it
  dies.
- With two bodies on the same block, right-clicking now opens the one you clicked. Before, only the
  first could ever be opened. The client part shows the clicked body's name and page count.
- Config file: `mixins.mtmixins.corpseloot.json`

## Ancient Warfare 2.9.20: traders (server)

- Faction traders now open their trade window on a dedicated server. Every right-click failed with
  `NoClassDefFoundError: net/minecraft/client/gui/GuiScreen`, because the mod read the shift key
  through a client-only class. The server's own sneak state is used instead, so a sneaking
  right-click still does not open the window.
- A faction trader left marked as busy by a window that was not closed properly is released, so it
  can trade again.
- Player-owned traders no longer take payment for a trade their backpack cannot fill. Repeating a
  trade with shift-click used to keep charging the player after the trader ran out of stock. Trades
  with no backpack are refused.
- Config file: `mixins.mtmixins.awtrade.json`

## AncientSpellcraft 1.8.3: charm and ring keybind (server)

- Activating a charm or ring with its keybind failed on a dedicated server
  (`NoClassDefFoundError: net/minecraft/client/Minecraft`) and left the item in the player's off hand.
- With two rings worn, activating the second could delete the first or copy the second into two
  slots. Activation now works on a dedicated server, and the item always goes back to the slot it came
  from. If that slot cannot take it back, it goes into the inventory, or is dropped at the player's
  feet.
- Config file: `mixins.mtmixins.asbauble.json`
