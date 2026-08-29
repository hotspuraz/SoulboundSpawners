# Changelog

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
