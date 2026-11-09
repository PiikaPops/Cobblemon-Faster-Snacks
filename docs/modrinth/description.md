Poké Snacks are great, until a few dozen of them start making your server stutter.

Every time a snack tries to attract a Pokémon, Cobblemon runs its whole spawning logic on a 17×17×17 area, in one go, on the main server thread. Most of that time goes into checking every spot of the area against every spawn entry, one condition at a time. Cobblemon Faster Snacks makes that work a lot cheaper without changing its result.

**Snacks behave exactly the same.** Same Pokémon, same places, same odds, same bites, same seasoning effects. The mod only skips work whose answer is already known.

![Time per snack spawn attempt](IMAGE_1_URL)

## What it does

- Checks each spawn condition once per distinct situation (light level, biome, block below...) instead of once per spot.
- Builds the per-spot data Cobblemon prepares (scripting context, nearby block list) only when a condition actually reads it.
- Reads sky light once per block instead of twice, and drops a player lookup that never changed anything.
- Caps snack work at 10 ms per tick. Attempts over the budget wait for the next tick, none are lost.

## Numbers

Measured on a dedicated server with Cobblemon 1.8.4, switching between vanilla and the mod within the same session.

- One snack spawn attempt on natural ground: 15.9 ms → 4.2 ms with a plain snack, 31.9 ms → 8.3 ms with a seasoned one.
- 64 snacks running at 20× speed: 58 ticks over 50 ms without the mod, 0 with it.
- On a sky farm platform, attempts are already cheap, so the average gain is smaller (11 to 24 %). The worst attempts still drop from about 40 ms to about 15 ms. If your lag comes from hundreds of Pokémon standing on platforms, this mod won't fix that part.

![Seasoned snack, biome by biome](IMAGE_2_URL)

![64 snacks at once](IMAGE_3_URL)

## Can I trust it?

You can check it in game: `/fastersnacks snacks verify true` makes every attempt compute both results, the mod's and Cobblemon's own, and counts the differences. During testing that was zero differences over more than a billion checks, with plain and seasoned snacks, and on a modpack running Lithium and ScalableLux.

## Install

Server side: put the jar in `mods` next to Cobblemon and Fabric API. Players don't need it. It also works in singleplayer.

Needs Minecraft 1.21.1, Fabric Loader 0.16+, Fabric API and Cobblemon 1.8.4.

## Commands

Operators only (permission level 3).

- `/fastersnacks stats`: how long snack attempts take, and how many waited for the next tick.
- `/fastersnacks optimize true|false`: turn the mod off and on without restarting, handy to compare.
- `/fastersnacks snacks verify true|false`: check mode. Slower, only for testing.

Settings are in `config/cobblemon_faster_snacks.json`.

## Good to know

- Made for Cobblemon 1.8.4. If a Cobblemon update changes the code this mod hooks into, the game stops at launch with an error instead of doing something wrong.
- Spawn conditions added by other mods still work, they just aren't sped up.
- The very first snack attempt after a restart still freezes for about a quarter of a second while Java warms up.
