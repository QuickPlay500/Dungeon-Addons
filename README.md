# Dungeon-Addons

> Do not use this mod unless you know exacly what you are doing or want to get banned very quickly!

A [NoammAddons](https://github.com/Noamm9/NoammAddons) addon for Hypixel Skyblock Dungeons. 

Works on Minecraft 26.2 and 26.3 with Fabric.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) (0.19.5 or newer) for Minecraft 26.2 or 26.3.
2. Put these mods into your `mods` folder:
   * NoammAddons, cheat version: [latest release on GitHub](https://github.com/Noamm9/NoammAddons/releases/latest). This addon is built against NoammAddons 1.3.5.
   * Fabric API: [Modrinth](https://modrinth.com/mod/fabric-api)
   * Fabric Language Kotlin: [Modrinth](https://modrinth.com/mod/fabric-language-kotlin)
   * Dungeon-Addons: the jar for your Minecraft version, `Dungeon-Addons-<version>-<minecraft>-cheat.jar`.
3. The features are in the NoammAddons settings (`/na`) under the ADDONS category.

## Features

### AutoTerm+InvWalk

Clicks terminals for you while hiding the gui so you can move and interact while an auto term is doing the terminal

### Inv Walk

Walk with your inventory open. WASD, jump and sneak keep working, while the mouse stays in the inventory and doesn't turn the camera.

* Containers: also walk in chests and other menus, not only your own inventory.
* Disable Sneak: shift doesn't make you sneak while a menu is open, so shift-clicking items doesn't make you crouch.

### Aura

Clicks things near you, even through walls, without you having to look at them. 

* Clear (outside the boss): Chests, Wither Essence, Levers, Redstone Skulls, Redstone Blocks (places the redstone key) and Mushrooms (broken instead of clicked). Each one is clicked once. A chest that doesn't open is tried again a bit later, the other secrets in range go first meanwhile.
* Puzzles:
   opens the reward chest once the puzzle is done.
* Boss (F7/M7 phase 3):
   * Terminals: opens the closest inactive terminal, not while one is open (a hidden AutoTerm+InvWalk one too).
   * Section Levers: flips the levers of every section once per P3.
   * Lights Device: flips the six right levers. If you pre-devved it, once P3 started it clicks the lowest middle lever to complete it, unless it's already active.
   * Arrow Device: once P3 started, turns the arrows that are still wrong.
* Options:
   * Close Chests: closes the chests it opened right away. The secret still counts.
   * No Heads With Wither Blade/AOTV: no clicks on player heads (wither essence and redstone skulls) while you hold a wither blade or AOTV, so the click doesn't use the item.
   * Server Rotation: turns your head onto each target for the server and other players before clicking, your screen doesn't move. Rotation Ticks sets how many ticks it takes there and back (1 is the quickest).

### Triggerbot

Clicks when your crosshair is on something that you want to click.

* Secrets : chests, levers, wither essence and redstone keys. 
* Terminals: opens the F7/M7 terminals.
* Levers: Lights Device flips the six right levers while they're off. Section Levers flips the levers of every section once per P3.
* Arrow Align: turns the arrows of the arrow device into place.
* No Heads With Wither Blade/AOTV: like in Aura.

### Auto Simon Says

### Auto Ice Fill

Etherwarps you block by block through Ice Fill with the etherwarp item from your hotbar (AOTV). Stand on the start of a part to begin. It swaps to the item, sneaks, warps along the solved path and swaps back at the end.

### Blessing Display

NoammAddons' Blessing Display as its own HUD element, with one extra option:

* Time As Power: a Time blessing is always 0 or 5, and Time V counts as 2.5 Power. This adds it to the Power line (Power 15 + Time V shows as Power 17.5) instead of showing Time on its own.

## Build

You need JDK 25.

```
./gradlew buildAll
```

The jars end up in `build/libs/`, one per Minecraft version. In IntelliJ you can also use the `Dungeon-Addons [buildAll]`, `Dungeon-Addons [runClient 26.2]` and `Dungeon-Addons [runClient 26.3]` run configs from `.run/`.

Based on the [Noamm-Squared](https://github.com/Noamm9/Noamm-Squared) addon template.
