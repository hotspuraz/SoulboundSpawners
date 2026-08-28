# SoulboundSpawners

Per-player owned ("soulbound") spawners with silk-touch mining, for Paper.
A dependency-free rebuild of the abandoned **MineableSpawners 3.1.5** +
**AtherialLibPlugin** stack, keeping every existing spawner, ownership record and
spawner item working.

- **Author:** ImEvo
- **Origin:** fergydanny's MineableSpawners, heavily modified 2024 by a freelancer,
  then rebuilt here.
- **Version:** 4.0.0

---

## Status — first build, not yet runtime-tested

This compiles cleanly against `paper-api 1.21.11` and produces
`build/libs/SoulboundSpawners-4.0.0.jar`. It has **not** been run on a live
server yet. Do not deploy to production before it has been through the
[test matrix](docs/BEHAVIOUR.md#test-matrix) on staging.

Known things to verify first are listed in [docs/BEHAVIOUR.md](docs/BEHAVIOUR.md#to-verify-on-staging).

## Building

Requires JDK 21 (or newer). From the project root:

```bash
./gradlew build
```

The jar lands in `build/libs/`. IntelliJ: just open the folder and run the
`build` task, or Build → Build Artifacts.

### Version target

The build compiles against **paper-api 1.21.11 / Java 21**. A plugin built
against an older API runs fine on a newer server, so this jar is expected to run
unchanged on 26.2. When every server is on 26.2 and you want to use newer APIs,
bump `paperApiVersion` and `targetJava` in `build.gradle.kts` and
`api-version` in `plugin.yml`.

## What it does

| Area | Behaviour |
|---|---|
| **Ownership** | Spawners of the types in `soulbound.types` (EVOKER, GHAST, IRON_GOLEM, PHANTOM, RAVAGER, SLIME, SHULKER, VINDICATOR, WITHER, WITCH) get a per-player owner. An owned spawner only spawns mobs while its owner is online, in the same world, and within `spawn-distance` (16) blocks. An owned-type spawner that isn't tracked spawns nothing. |
| **Mining** | Silk-touch a spawner with a pickaxe to get it as an item. No permission required (per your config). The item is owned by the miner if the type is soulbound. |
| **Placing** | Placing a spawner item applies its type; if it carries an owner, it's registered. You can't place another player's soulbound spawner. |
| **Spawn-rate throttle** | Above `performance.player-threshold` players online, a share of *all* spawner spawns is cancelled. This is SBS's only spawn limiting — block counts stay Insights' job, live-entity counts stay MobFarmManager's. |
| **Protection** | Spawners survive explosions and the wither / ender dragon, and can't be renamed in anvils. All behind config flags (default on — matches current server behaviour). |
| **Selling** | Owned spawner items are blocked from being listed/sold via the plugins named in `sell-guard.events`. |
| **Commands** | See below. |

### Commands (`/soulboundspawners`, alias `/sbs`)

```
/sbs item type <type>         held spawner  – retype (soulbound => owner = you)
/sbs item owner <player|none> held spawner  – set / clear owner
/sbs type <type>              looked-at spawner – retype
/sbs transfer <player>        looked-at spawner – change owner
/sbs unclaim                  looked-at spawner – remove owner
/sbs info                     looked-at spawner – show type / owner / tracked
/sbs give <player> <type> <amount>
/sbs types
/sbs reload
/sbs status                   storage + cache health
/sbs migrate confirm          one-time MySQL -> SQLite import (run with 0 players)
```

Permissions are `soulboundspawners.*` (`.bypass`, `.give`, `.type`, `.item`,
`.transfer`, `.unclaim`, `.info`, `.types`, `.reload`, `.migrate`, `.status`,
`.nosilk`, `.mine`, `.admin`). While `legacy-permissions: true` the old
`mineablespawners.*` nodes are also honoured, so LuckPerms can be migrated
gradually.

## First start

1. On first enable, if `plugins/MineableSpawners/` exists, `config.yml` and
   `newC.yml` are imported into `plugins/SoulboundSpawners/config.yml`
   (a `.imported` marker stops it re-running). Review the result.
2. The SQLite database `plugins/SoulboundSpawners/spawners.db` is created empty.
3. Run **`/sbs migrate confirm`** once, with no players online, to import the old
   `mspawners` MySQL table. See [docs/MIGRATION.md](docs/MIGRATION.md).

## No external dependencies

- **NBT** is read/written through the Bukkit PersistentDataContainer for new
  items, and the old `ms_mob` / `ms_owner` tags are read straight out of the
  item's NBT string. No NBT-API, no NBTAPI plugin.
- **SQLite** driver comes from the `libraries:` entry in `plugin.yml` (Paper
  downloads it once). If your Paper already bundles it, that's a no-op. If it's
  ever missing the plugin loads in a safe degraded mode.
- **Vault** and the auction/shop plugins are reached by reflection — nothing to
  install for the build.

## Layout

```
config/      config model + one-time MineableSpawners import
spawner/     item read/write (all formats), soulbound type set
ownership/   in-memory index + records
storage/     SQLite store, MySQL->SQLite migration
listener/    mine, place, spawn (ownership + throttle), protection, anvil, join-fix
hook/        Vault (reflective), sell-guard (reflective)
command/     /sbs
```
