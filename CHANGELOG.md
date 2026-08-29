# Changelog

## 4.0.8

- After a migration (and on shutdown) the SQLite WAL is checkpointed into the
  main `spawners.db` file, so it's a complete, backup-ready file immediately
  rather than only after the next restart. (Your data was always safe — it just
  lived in `spawners.db-wal` until a checkpoint.)

## 4.0.7

- `/sbs migrate` can now read from an explicit database instead of only
  `plugins/AtherialLibPlugin/database.yml`. Fill in the new `migration:` section
  of config.yml (`host` / `port` / `database` / `username` / `password` /
  `table`) to point it at a test copy of production. Leave `database` blank to
  keep auto-discovering. `/sbs migrate` (no confirm) and the start message now
  print which source it will use.

## 4.0.6

- **Insights integration.** Something on your server cancels the spawner
  `BlockBreakEvent` (that's why the safety-net fires) — Insights only counts
  removals on an un-cancelled break at MONITOR, so its per-chunk spawner count
  was getting stuck. When our safety net has to force a spawner removal, it now
  invalidates Insights' cached count for that chunk; Insights re-scans it on the
  next placement and gets the true number. Shown in `/sbs status` as
  `Insights hook: true`. No compile-time dependency — pure reflection, disables
  itself on any error.
- Still worth finding *what* cancels the break (debug `[mine]` line + bisect) —
  that's the real fix; this keeps Insights honest in the meantime. MobFarmManager
  has the same 4-per-chunk spawner cap and no hook yet, so if it's *also* stuck,
  that points at the same underlying canceller.

## 4.0.5

- **All in-world warnings now show on the action bar** (uniform), never chat:
  not-owner, blacklisted-world, bypass messages, transaction messages, anvil,
  sell-guard. Command output (`/sbs info`, `reload`, etc.) stays in chat.
- **Insights / MobFarmManager placement limit fix, take 2:** the mine listener
  moved to `HIGHEST` and now *un-cancels* a spawner break that a region/anticheat
  plugin blocked (new `mining.force-break: true`, default on). The break event
  then completes for real, so per-chunk limiters count the removal and let
  players place again. `mining.force-break: false` restores "let those plugins
  veto spawner mining".
  - *A limiter count that's already stuck needs one rescan / restart to clear.*

## 4.0.4

- **Fix:** mining a spawner no longer confuses per-chunk block limiters (Insights,
  MobFarmManager). The mine flow now lets the vanilla break event go through
  (drops suppressed) so those plugins see a normal removal, instead of cancelling
  it and swapping the block to air behind their back. A next-tick safety net
  still removes the block if another plugin vetoes the break after us.
  - *Insights counts that are already wrong from earlier builds won't self-heal —
    run `/insights scan` (or Insights' area rescan) on the affected chunks once.*
- **Fix:** `/sbs give`, `/sbs type`, `/sbs item type` now tab-complete **all**
  mob types, not just the soulbound ones.
- Default `no-permission` message reworded ("You need a higher rank to mine
  spawners!"). It's fully configurable under `mining.messages`.
- New config keys are now merged into an existing `config.yml` on start (existing
  values kept). Changed *default text* still needs a manual edit or a config
  regen.

## 4.0.3

- `mining.require-permission` now ships **`true`** — players need
  `soulboundspawners.mine` to mine spawners.
- Mining checks reordered and messaged distinctly:
  1. no `soulboundspawners.mine` → *"You don't have permission to mine spawners.
     (soulboundspawners.mine)"* (action bar)
  2. spawner owned by someone else → *"This spawner belongs to `<name>`."* (chat)
- `messages.not-owner-break` / `not-owner-place` support `%owner%`.

> Existing `plugins/SoulboundSpawners/config.yml` files keep their old values —
> either edit `mining.require-permission` + the message lines by hand, or delete
> the file (and the `.imported` marker) and let it regenerate.

## 4.0.2

- Mining feedback ("no silk touch", "wrong tool", "no permission", "out of luck",
  etc.) now shows on the **action bar** instead of chat, so repeatedly clicking a
  spawner you can't mine no longer spams chat. Default wording rewritten to say
  the actual reason.
- `global.debug: true` logs which gate blocked a mine attempt (`[mine] blocked …
  reason="…"`).
- No behaviour change to *who* can mine — a normal player still needs only a
  Silk Touch pickaxe, unless `mining.require-permission` is set to `true` in
  config (it ships `false`; check your file if you're seeing a permission
  message).

## 4.0.1

- **Fix:** mined spawner block stayed in the world (player got the item but the
  block didn't break) on servers that protect spawner breaks after our event
  handler. The mine flow now takes over the break — cancels the event, removes
  the block itself, plays a break sound, and drops mining exp manually when
  `mining.drop-exp` is on.
- `global.debug: true` now logs each mine attempt (`[mine] … cancelledOnEntry=…`)
  so a lower-priority protection plugin can be identified.
- Build: disabled Gradle configuration cache (was serving a stale `plugin.yml`).

## 4.0.0

First build. Standalone, dependency-free rebuild of MineableSpawners 3.1.5 +
AtherialLibPlugin.

- No AtherialLib, no NBT-API / NBTAPI plugin, no XSeries. Paper API only; SQLite
  via `plugin.yml` `libraries:`.
- Ownership model ported from the deployed MineableSpawners jar (soulbound types,
  owner-online-within-16-blocks spawn gate) with the audit fixes: cross-world
  crash, null-owner NPEs, `MONITOR`→`HIGH`, charge-on-grant ordering, ordered
  permission-chance map, DB failure fails safe (degraded mode) instead of
  silently disabling every soulbound spawner.
- Item compatibility: reads new PDC tags, legacy root `ms_mob`/`ms_owner` NBT,
  and v1/v2 name/lore formats. Join-time fixer upgrades old items in place.
  `ms_*` tag names kept.
- `/sbs migrate` — one-time MySQL `mspawners` → SQLite (reads only; dedupes on
  location; JSON backup + reconciliation report).
- Full rebrand: SoulboundSpawners / ImEvo, `/sbs` command, `soulboundspawners.*`
  permissions (old `mineablespawners.*` honoured while `legacy-permissions: true`).
- New: `/sbs transfer|unclaim|item|info`, spawn-rate throttle (`performance.*`),
  sell-guard for AxAuctions / ChestShop, per-world placement blacklist.
- Removed: spawn-egg retyping, zAuctionHouse hook, ShopGUI+ provider, startup
  entity-type dump, `/ms` command.
