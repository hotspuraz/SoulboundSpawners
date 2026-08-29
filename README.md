# SoulboundSpawners

Per-player owned ("soulbound") spawners with silk-touch mining, for Paper.

A dependency-free rebuild of the abandoned **MineableSpawners 3.1.5 +
AtherialLibPlugin** stack — same behaviour, no AtherialLib, no NBT-API, on
SQLite instead of MySQL, and backwards-compatible with every spawner, ownership
record and spawner item that already exists.

- **Author:** ImEvo
- **Origin:** fergydanny's MineableSpawners, heavily modified in 2024 by a
  freelancer, then rebuilt here.
- **Current version:** 4.3.2

---

## Contents

- [What it does](#what-it-does)
- [Requirements](#requirements)
- [Installation](#installation)
- [The soulbound model](#the-soulbound-model)
- [Commands](#commands)
- [Permissions](#permissions)
- [Configuration reference](#configuration-reference)
- [The migration](#the-migration)
- [Spawner items & backwards compatibility](#spawner-items--backwards-compatibility)
- [Other plugins](#other-plugins)
- [Rollout & rollback](#rollout--rollback)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [Where things live](#where-things-live)

---

## What it does

| Area | Behaviour |
|---|---|
| **Ownership** | Spawners of the types in `soulbound.types` get a per-player owner. An owned spawner only spawns mobs while its owner is **online, in the same world, and within `spawn-distance` (16) blocks**. An owned-type spawner that isn't tracked spawns nothing. |
| **Mining** | Silk-touch a spawner with a pickaxe to get it as an item. Gated behind `soulboundspawners.mine` by default. The item is owned by the miner if the type is soulbound. |
| **Placing** | Placing a spawner item applies its type; if it carries an owner it's registered. You can't place another player's soulbound spawner. |
| **Spawn-rate throttle** | Above `performance.player-threshold` players online, a share of *all* spawner spawns is cancelled. This is SBS's only spawn limiting — block counts are Insights' job, live-entity counts are MobFarmManager's. |
| **Protection** | Spawners survive explosions and the wither / ender dragon and can't be renamed in anvils (config flags, default on). Changing a spawner's type with a spawn egg is disabled outright for everyone (no config, no bypass). |
| **Selling** | Owned spawner items are blocked from being listed/sold via the plugins named in `sell-guard.events`. |
| **Persistence** | SQLite (`plugins/SoulboundSpawners/spawners.db`), WAL mode, single writer thread. Survives restarts. Fails **safe** if the DB is unavailable (see [degraded mode](#degraded-mode)). |

## Requirements

- **Paper** (or a Paper fork). Not tested on Spigot.
- Compiled against `paper-api 1.21.11` / Java 21; runs unchanged on newer
  (e.g. 26.2). See [Building from source](#building-from-source) to bump the target.
- A SQLite JDBC driver — normally handled automatically (see
  [Troubleshooting → degraded mode](#degraded-mode)).
- **Optional:** Vault + an economy (only if you turn charging on), Insights,
  AxAuctions / ChestShop, MarriageMaster (soulmate features), PlaceholderAPI.
  All reached softly — nothing is required.

## Installation

### Fresh server (no existing spawner data)

1. Drop `SoulboundSpawners-<version>.jar` in `plugins/`.
2. Start. It creates `plugins/SoulboundSpawners/config.yml` and an empty
   `spawners.db`.
3. Grant your player rank `soulboundspawners.mine` (and give staff
   `soulboundspawners.admin`).

### Replacing MineableSpawners

1. **Back up the server** (world folders + `plugins/`).
2. Stop the server.
3. Remove `MineableSpawners.jar`. Add `SoulboundSpawners-<version>.jar`.
   Leave `AtherialLibPlugin.jar` and the `plugins/MineableSpawners/` folder in
   place for now.
4. Start **with the whitelist on**. On first start, if `plugins/MineableSpawners/`
   exists, `config.yml` + `newC.yml` are imported into
   `plugins/SoulboundSpawners/config.yml` (a `.imported` marker stops it
   re-running). Review the result.
5. `/sbs status` → `Storage healthy: true`.
6. Run **`/sbs migrate confirm`** once, offline — see [The migration](#the-migration).
7. `/sbs audit` and `/sbs audit full`, then open up.

Full step-by-step: **[docs/ROLLOUT.md](docs/ROLLOUT.md)**.

## The soulbound model

- **Soulbound types** are listed in `soulbound.types` (default: EVOKER, GHAST,
  IRON_GOLEM, PHANTOM, RAVAGER, SLIME, SHULKER, VINDICATOR, WITHER, WITCH).
- A spawner of a soulbound type carries a **per-player owner**, stored two ways:
  - on the placed block → a row in `spawners.db` keyed by `world, x, y, z`
  - on the item → NBT tags `ms_mob` / `ms_owner` (see
    [Spawner items](#spawner-items--backwards-compatibility))
- **Spawn rules for an owned spawner** (`SpawnerSpawnEvent`):
  1. soulbound type + not tracked → no spawn
  2. owner offline → no spawn
  3. owner in a different world → no spawn
  4. owner farther than `spawn-distance` → no spawn
  5. otherwise → spawn (then the rate throttle may still cull it)
- **Non-soulbound spawners** (zombie, skeleton, pig, …) are never owned and
  behave like vanilla — anyone with `soulboundspawners.mine` can silk them, they
  spawn regardless of who's nearby.
- Mining an owned spawner returns an item **owned by the miner**. Mining
  someone else's is blocked unless you have `soulboundspawners.bypass`.

### Soulmates (MarriageMaster)

If **MarriageMaster** is installed, a spawner owner's married partner is treated
like a co-owner (config `soulmate`, all default on):

- the owner's soulbound spawners spawn while the **partner** is nearby, even if
  the owner is offline (`soulmate.spawn-for-partner`)
- the partner can **mine** them — the spawner stays owned by the original owner,
  not transferred to the partner (`soulmate.can-mine`)
- the partner can **place** the owner's spawner items (`soulmate.can-place`)

`soulmate.enabled: false` turns the whole thing off. If MarriageMaster isn't
installed, these settings do nothing.

Individual owners can opt their partner out with **`/sbs partner`**:

| Command | Effect |
|---|---|
| `/sbs partner` | show your three toggles and their state |
| `/sbs partner mine <on\|off>` | let your partner mine your spawners |
| `/sbs partner place <on\|off>` | let your partner place your spawner items |
| `/sbs partner spawn <on\|off>` | let your spawners run while only your partner is nearby |

All default **on**. A toggle can only *restrict* — it never grants more than the
server-wide `soulmate.*` config allows. Choices persist in the `player_prefs`
table and are saved even while soulmate features are disabled server-wide.
Permission `soulboundspawners.partner`, default `true`.

### Degraded mode

If `spawners.db` can't be opened or read at startup, the plugin logs loudly and
enters **degraded mode**: ownership rules are suspended (so owned spawners keep
working rather than all going dark), and nothing new is tracked until it's fixed
and reloaded. `/sbs status` shows `Degraded mode: true`. This is deliberately
safer than the old stack, which silently switched every soulbound spawner off on
a DB hiccup.

## Commands

`/soulboundspawners`, alias `/sbs`.

| Command | Target | Effect |
|---|---|---|
| `/sbs type <type>` | placed spawner you look at | retype it; if soulbound, you become the owner |
| `/sbs transfer <player>` | placed spawner you look at | change the owner |
| `/sbs unclaim` | placed spawner you look at | remove ownership (becomes untracked) |
| `/sbs info` | placed spawner you look at | show type / owner / tracked status |
| `/sbs item type <type>` | spawner in your hand | retype the item |
| `/sbs item owner <player>` | spawner in your hand | set the item's owner |
| `/sbs item owner none` | spawner in your hand | clear the item's owner |
| `/sbs give <player> <type> <amount>` | — | give spawner item(s) |
| `/sbs types` | — | list mob types |
| `/sbs reload` | — | reload config + re-read the ownership cache |
| `/sbs status` | — | storage health, cache size, hooks |
| `/sbs audit` | — | data health report (loaded chunks only) — [see below](#sbs-audit) |
| `/sbs audit full` | — | loads every chunk with a tracked spawner and checks all of them |
| `/sbs partner [mine\|place\|spawn] [on\|off]` | — | control what your married partner may do with your spawners — [see above](#soulmates-marriagemaster) |
| `/sbs migrate confirm` | — | one-time MySQL → SQLite import (run offline) |

"Look at" commands raycast ~6 blocks.

### `/sbs audit`

Read-only. Nothing is changed. Reports:

- **cache vs DB** row count (flags a mismatch)
- **null / no owner** rows
- **owner never joined this server** — benign; the spawner activates when they
  next log in. High counts on a copied test server are normal (that box doesn't
  have everyone's player-data).
- **invalid entity type** rows
- rows in an **unknown world**
- **block check** — how many tracked spawners still have a spawner block.
  `/sbs audit` only checks already-loaded chunks; `/sbs audit full` loads them
  all (6 chunks/tick, unloads what it loaded, ~minor lag). A spawner with no
  block ("gone") means the block was removed some time after the DB row was
  written — harmless, but you may want to clean those up.

Full locations for anything flagged go to console with an `[audit]` prefix.

## Permissions

`default: op` = operators get it automatically (grant to staff via LuckPerms).
`default: false` = nobody until granted.

### Gameplay

| Node | Effect | Default |
|---|---|---|
| `soulboundspawners.bypass` | Break/place any spawner ignoring ownership; enables the sneak-to-mine-as-admin path | op |
| `soulboundspawners.nosilk` | Mine spawners without silk touch | false |
| `soulboundspawners.mine` | Mine spawners — enforced only when `mining.require-permission: true` (it is, by default) | false |
| `soulboundspawners.mine.<type>` | Per-type, e.g. `.mine.witch` — enforced only when `mining.require-individual-permission: true` | — |

### Commands

| Node | Command | Default |
|---|---|---|
| `soulboundspawners.give` | `/sbs give` | op |
| `soulboundspawners.type` | `/sbs type`, `/sbs item type` | op |
| `soulboundspawners.item` | `/sbs item` | op |
| `soulboundspawners.transfer` | `/sbs transfer` | op |
| `soulboundspawners.unclaim` | `/sbs unclaim` | op |
| `soulboundspawners.info` | `/sbs info` | op |
| `soulboundspawners.types` | `/sbs types` | op |
| `soulboundspawners.reload` | `/sbs reload` | op |
| `soulboundspawners.status` | `/sbs status` | op |
| `soulboundspawners.audit` | `/sbs audit` | op |
| `soulboundspawners.partner` | `/sbs partner` | **true** |
| `soulboundspawners.migrate` | `/sbs migrate` | op |
| `soulboundspawners.admin` | everything above | op |

### Legacy nodes

While `legacy-permissions: true` (default), these old MineableSpawners nodes are
also accepted, so LuckPerms can be migrated gradually:

`mineablespawners.bypass` → `.bypass` · `mineablespawners.nosilk` → `.nosilk` ·
`mineablespawners.mine[.<type>]` → `.mine[.<type>]` · `mineablespawners.give` →
`.give` · `mineablespawners.set` → `.type` · `mineablespawners.types` → `.types`
· `mineablespawners.reload` → `.reload`

Set `legacy-permissions: false` once every group uses the new nodes.

## Configuration reference

`plugins/SoulboundSpawners/config.yml`. `/sbs reload` re-reads it.

> New keys added in a plugin update are merged into your file automatically on
> start. **Changed default *text*** (messages) is **not** — edit those by hand or
> delete `config.yml` (and `.imported`) to regenerate.

### Top level

| Key | Default | Meaning |
|---|---|---|
| `legacy-permissions` | `true` | also accept `mineablespawners.*` nodes |

### `global`

| Key | Default | Meaning |
|---|---|---|
| `backwards-compatibility` | `true` | parse the mob type from an old item's name/lore when it has no NBT |
| `fix-items-on-join` | `true` | rewrite legacy spawner items in a player's inventory on join |
| `debug` | `false` | verbose console logging (`[mine]`, join-fixer counts, etc.) |
| `display.name` | `&8[&e%mob% &7Spawner&8]` | item name — `%mob%` |
| `display.lore` | (list) | item lore — `%mob%`, `%owner%` (owner line only rendered on owned soulbound items) |
| `display.lore-enabled` | `true` | apply the lore |

### `soulbound`

| Key | Default | Meaning |
|---|---|---|
| `types` | (10 mobs) | which entity types are owned/private |
| `spawn-distance` | `16` | owner must be within this many blocks for an owned spawner to spawn |

### `soulmate` — MarriageMaster

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | master switch (no-op without MarriageMaster) |
| `can-mine` | `true` | partner can mine the owner's spawners (stays owned by the owner) |
| `can-place` | `true` | partner can place the owner's spawner items |
| `spawn-for-partner` | `true` | owner's spawners spawn while the partner is nearby |

### `performance` — spawn-rate throttle

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | |
| `player-threshold` | `45` | throttle kicks in above this many players online |
| `spawn-reduction-percent` | `80.0` | chance each spawner spawn is cancelled while throttling |

### `storage`

| Key | Default | Meaning |
|---|---|---|
| `file` | `spawners.db` | SQLite filename inside the plugin folder |

### `migration`

Where `/sbs migrate` reads from. Leave `database` blank to auto-discover from
`plugins/AtherialLibPlugin/database.yml`. Fill it in to point at a specific DB
(e.g. a test copy). Keys: `host`, `port`, `database`, `username`, `password`,
`table` (default `mspawners`).

### `protection`

| Key | Default | Meaning |
|---|---|---|
| `block-explosions` | `true` | spawners survive TNT / creeper / bed explosions |
| `block-wither` | `true` | spawners survive the wither & ender dragon |
| `prevent-anvil-rename` | `true` | can't rename spawner items in an anvil |

Changing a spawner's type by right-clicking it with a spawn egg is disabled for
everyone — no config key, no permission, no bypass. Set spawner types with
`/sbs type` or `/sbs item type`.

### `sell-guard`

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | |
| `events` | (2 ChestShop events) | fully-qualified `Cancellable` event classes to intercept. If one exposes an `ItemStack` and it's owned, the event is cancelled. Add your AxAuctions / other listing-event classes here — the console prints `[sell-guard] Watching …` for each one it hooks on startup. |

### `mining`

| Key | Default | Meaning |
|---|---|---|
| `require-permission` | `true` | need `soulboundspawners.mine` |
| `require-individual-permission` | `false` | need `soulboundspawners.mine.<type>` |
| `force-break` | `true` | break the spawner even if a region/anticheat plugin cancelled the event (keeps Insights / MobFarmManager counts honest). `false` = let those plugins veto mining. |
| `any-pickaxe` | `true` | any pickaxe valid on the running MC version works |
| `tools` | (pickaxe list) | extra explicit tool ids (used when `any-pickaxe: false`, or in addition) |
| `require-silktouch` | `true` | |
| `require-silktouch-level` / `required-level` | `false` / `2` | require a minimum Silk Touch level |
| `chance` | `100.0` | % chance the spawner drops on a successful mine |
| `use-perm-based-chances` / `perm-based-chances` | `false` / (list) | drop chance gated behind arbitrary permission nodes (`node:percent`, first match wins, config order respected) |
| `drop-to-inventory` | `true` | put the spawner straight in the inventory vs on the ground |
| `drop-exp` | `false` | drop the vanilla mining XP |
| `still-break` | `false` | if a requirement fails, break the spawner anyway (no drop) vs cancel the break |
| `charge` / `prices` | `false` / (`TYPE:amount`, or `ALL:amount`) | require Vault money to mine |
| `blacklisted-worlds` | `[]` | worlds where spawners can't be mined |
| `messages.*` | | action-bar feedback strings |
| `requirements.*` | | phrases substituted into the `still-break` message |

### `placing`

| Key | Default | Meaning |
|---|---|---|
| `log` | `true` | log every spawner placement to console |
| `charge` / `prices` | `false` / (list) | require Vault money to place |
| `blacklisted-worlds` | `[]` | worlds where spawners can't be placed |
| `messages.*` | | feedback strings |

### `give` / `type` / `types`

| Key | Default | Meaning |
|---|---|---|
| `give.require-permission` | `true` | need `soulboundspawners.give` |
| `give.drop-if-full` | `true` | drop overflow at the target's feet vs tell the sender |
| `give.messages.*` | | |
| `type.require-permission` / `type.require-individual-permission` | `true` / `true` | gate `/sbs type` |
| `types.require-permission` | `true` | gate `/sbs types` |
| `types.message-title` / `types.message-entry` | | `/sbs types` formatting — `%mob%` |

## The migration

One-time, one server at a time, **with no players online**.

1. `/sbs migrate confirm` reads `plugins/AtherialLibPlugin/database.yml` (or the
   `migration:` config section) for a MySQL connection and runs a **single
   `SELECT uuid, location, type, owner FROM mspawners`**. It never writes to,
   alters or drops that table.
2. Each row: parse `location` (`world,x,y,z`), `owner` (UUID or none), keep `type`.
   Duplicate locations are collapsed (kept one, counted).
3. A full snapshot is written to
   `plugins/SoulboundSpawners/migration-backup-<timestamp>.json`.
4. The de-duplicated rows are inserted into `spawners.db` in one transaction, and
   the WAL is checkpointed so the `.db` file is complete on disk immediately.
5. A reconciliation report is printed and the ownership cache reloaded.

**Verify:** `rows read` = the table's row count; `bad locations` / `bad owners`
= 0; `imported` = `rows read − duplicates collapsed`; `/sbs status` `Rows in DB`
and `Tracked spawners` = `imported`.

The MySQL `mspawners` table is your primary backup (untouched — re-migrate any
time). The JSON is the secondary copy — archive it off-server, keep it until the
server's been happy on SQLite for weeks. For read-only extra safety, point the
migration at a **read-only MySQL user**.

**MySQL driver:** `/sbs migrate` needs a MySQL/MariaDB JDBC driver. If none is on
the classpath, drop `mysql-connector-j-*.jar` into
`plugins/SoulboundSpawners/driver/` and re-run.

## Spawner items & backwards compatibility

Read order for a spawner item's type / owner:

1. **Modern:** Bukkit `PersistentDataContainer` keys `soulboundspawners:mob` /
   `:owner` — what *new* items use.
2. **Legacy NBT:** root tags `ms_mob` / `ms_owner` — what current MineableSpawners
   items carry. Read out of the item's serialised NBT.
3. **v2:** type in the display name (`[Zombie] Spawner`) — only if
   `global.backwards-compatibility: true`.
4. **v1:** type in a lore line (`Type: §7ZOMBIE`) — same flag.

The `ms_mob` / `ms_owner` tag names are **kept** despite the rename, so nothing
in a chest breaks. New items get the modern PDC keys; the join-time fixer
(`fix-items-on-join`) upgrades legacy items in a player's inventory as they log
in. Items in closed containers are never touched until a player places one — and
placement reads all four formats.

## Other plugins

| Plugin | Interaction |
|---|---|
| **Insights** | Keeps its per-chunk spawner **block** limit. SBS forces the break through (`mining.force-break`) so Insights' MONITOR listener counts the removal; a reflective hook also invalidates Insights' chunk cache if a break still gets vetoed. `/sbs status` → `Insights hook`. |
| **MobFarmManager** | Keeps its live-**entity** limits. Untouched by SBS. |
| **CoreProtect** | Sees a normal block break/place for spawners (SBS doesn't cancel-and-replace). |
| **GriefPrevention / WorldGuard** | Claim/region protection runs first (LOWEST/LOW). If it denies the break, SBS respects it *unless* `mining.force-break: true` and the player passed SBS's own checks. Set `force-break: false` to always defer to region protection. |
| **Vault** | Optional. Only used when `mining.charge` / `placing.charge` is on. Reflective — no hard dependency. |
| **AxAuctions / ChestShop / SellChest** | `sell-guard` blocks owned items from being listed/sold. Add the real listing-event classes to `sell-guard.events` (check the `[sell-guard] Watching …` startup lines). |
| **MarriageMaster** | Optional. When present, a spawner owner's married partner can use their soulbound spawners — see [Soulmates](#soulmates-marriagemaster). Reflective — no hard dependency. |
| **AtherialLibPlugin** | **Not** a dependency. Only its `database.yml` file is read, and only by `/sbs migrate`. Can be removed once nothing else on the server uses it. |

## Rollout & rollback

See **[docs/ROLLOUT.md](docs/ROLLOUT.md)** for the full per-server runbook. Summary:

- One server at a time, quietest first.
- Backup → stop → swap jar (keep AtherialLibPlugin) → start whitelisted →
  `/sbs migrate confirm` → verify report + `/sbs audit full` → open up → monitor.
- **Rollback:** stop → swap `MineableSpawners.jar` back in → keep
  `AtherialLibPlugin.jar` → start. The MySQL table was only read, so the old
  plugin resumes exactly where it left off. Ownership changes made after cutover
  are lost (nothing, if the window was short).
- Weeks later: drop `AtherialLibPlugin.jar` if unused, set
  `legacy-permissions: false`, delete `plugins/MineableSpawners/`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Player gets **"You need a higher rank to mine spawners!"** | working as intended — `mining.require-permission: true`. Grant `soulboundspawners.mine`, or set the flag `false`. |
| `spawners.db` is only ~12 KiB after migrating | normal — the rows are in `spawners.db-wal` until a checkpoint. 4.0.8+ checkpoints after migration and on shutdown. Data is fine (`/sbs status` proves it). |
| **`/sbs audit full` shows `present 0`** | the server's world isn't a copy of the one the DB coordinates came from. Expected on a test box with a fresh world; on the real server it should show ~all present. |
| `/sbs status` → **`Storage healthy: false` / degraded** | no SQLite driver. Drop `sqlite-jdbc-*.jar` into `plugins/SoulboundSpawners/driver/` and restart, or ensure the server had internet on first start (Paper downloads it via `libraries:`). |
| `/sbs migrate` → **"No MySQL/MariaDB JDBC driver found"** | drop `mysql-connector-j-*.jar` into `plugins/SoulboundSpawners/driver/`. |
| `/sbs migrate` → **"Table 'x.mspawners' doesn't exist"** | it connected but that database has no data. Point `migration:` (or `database.yml`) at the database that actually holds `mspawners`. |
| Changed a `messages:` line, `/sbs reload`, no effect | config merge adds *new* keys but doesn't overwrite existing values/text. Edit the line directly, or regenerate the file. |
| Owned spawner isn't spawning | check `/sbs info` — is it tracked, right owner? Owner must be **online, same world, ≤ `spawn-distance` blocks**. Then check the throttle (`performance`) and MobFarmManager's entity cap. |
| Spawn-egg no longer changes spawner type | intended and permanent — the vanilla behaviour is disabled for everyone, as in the original plugin. Retype via `/sbs type` / `/sbs item type`. |

Turn on `global.debug: true` + `/sbs reload` for `[mine]` / `[audit]` /
join-fixer logging.

## Building from source

```bash
./gradlew build
```

Requires JDK 21+. Output: `build/libs/SoulboundSpawners-<version>.jar`.

To target a newer Paper API: bump `paperApiVersion` and `targetJava` in
`build.gradle.kts` and `api-version` in `src/main/resources/plugin.yml`. Bump
`version` in `gradle.properties` per release; `CHANGELOG.md` tracks each.

## Where things live

```
src/main/java/evo/soulboundspawners/
  SoulboundSpawnersPlugin.java   main class, wiring, reload
  Text.java / Permissions.java   colour codes / permission checks (incl. legacy)
  config/                        typed config view, one-time MineableSpawners import, price parsing
  spawner/                       item read/write (all 4 formats), the soulbound type set
  ownership/                     BlockKey, OwnedSpawner record, the in-memory index + degraded mode, partner-prefs cache
  storage/                       SQLite store (single writer, WAL) incl. player_prefs, MySQL->SQLite migration
  listener/                      mine, place, spawn (ownership gate + throttle), protection, anvil, join item-fix
  hook/                          Vault, sell-guard, Insights, MarriageMaster (all reflective)
  command/                       /sbs dispatch, /sbs audit runner

docs/
  BEHAVIOUR.md    the compatibility contract + full test matrix
  TESTING.md      how to set up a test server (3 tiers)
  MIGRATION.md    migration detail
  ROLLOUT.md      per-server production runbook
CHANGELOG.md
```
