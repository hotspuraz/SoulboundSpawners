# Behaviour spec & compatibility contract

Ground truth for the rebuild is the **deployed `MineableSpawners.jar` (3.1.5)**,
not the `IdeaProjects/MinecableSpawners` source (which is an older snapshot). The
deployed jar = that source plus four deltas: standalone Vault economy, no
zAuctionHouse, NBT-API via AtherialLibPlugin, newer XSeries. Core behaviour is
identical.

## The three compatibility boundaries

### 1. Placed spawner blocks
A spawner is "owned" only by a match in the database, keyed by
**world name + block X/Y/Z** — no marker on the block. `BlockKey.of(Location)`
reproduces the old `AtherialXYZLocation.fromLocation(loc, true)` derivation
exactly (`world.getName()`, `getBlockX/Y/Z`). Every currently-owned spawner keeps
its owner.

### 2. Ownership rows (database)
Old: MySQL table `mspawners(uuid, location, type, owner)`, `location` =
`"world,x,y,z"`, `owner` = UUID string or blank.
New: SQLite `spawners(world, x, y, z, entity_type, owner, created_at)`, primary
key on the location. Populated once by `/sbs migrate` — see
[MIGRATION.md](MIGRATION.md). The MySQL table is only read, never changed.

Production data checked (server s5, 5,950 rows): all 10 types are exactly the
soulbound list, 3 worlds, 531 owners, **no null owners**, all locations clean,
9 duplicate-location rows (collapsed by the migration).

### 3. Spawner items (inventories, chests, shulkers, ender chests)
Read order in `SpawnerItems`:
1. **PDC** keys `soulboundspawners:mob` / `:owner` — what *new* items use.
2. **Legacy root NBT** `ms_mob` / `ms_owner` — what current MineableSpawners
   items carry (written by NBT-API into the custom-data component). Read out of
   `ItemMeta#getAsString()` with a regex.
3. **v2** — type in the display name (`[Zombie] Spawner`), when
   `global.backwards-compatibility` is on (**it is, on your server**).
4. **v1** — type in a lore line (`Type: §7ZOMBIE`), same flag.

The `ms_mob` / `ms_owner` tag names are deliberately **kept** despite the plugin
rename — every item in circulation uses them. New items also get them via
`getAsString()`-visible… no: new items use PDC; the join-time fixer upgrades old
items to PDC form as players log in. Legacy readers stay forever (cheap).

`create()` only writes an owner for soulbound types — matches the old
`API.getSpawnerFromEntityType`.

## Ownership rules (SpawnerSpawnListener)

Preserved from the deployed jar, with the cross-world crash fixed:

- soulbound entity type **and** location not tracked → cancel the spawn
- location not tracked (non-soulbound) → allow
- owner offline → cancel
- owner in a different world → cancel (old code threw `IllegalArgumentException` here)
- owner farther than `spawn-distance` → cancel
- **degraded mode** (DB unavailable at start): the whole gate above is skipped so
  owned spawners keep working; nothing new is registered; `/sbs status` shows it.
  The old stack instead silently switched every soulbound spawner off.

## Mining rules (SpawnerMineListener) — ported from deployed jar

Runs at `HIGH` (was `MONITOR` — illegal to cancel there). Order:
1. ownership check — non-owner without bypass → cancelled + message
2. exp suppression
3. admin bypass (creative or `.bypass`): must be sneaking, else cancelled;
   preserves original owner on the dropped item
4. blacklisted world
5. `mine` / `mine.<type>` permission (both off on your server)
6. tool check — any pickaxe if `mining.any-pickaxe`, else the `tools` list
   (`GOLD_PICKAXE` also matches `GOLDEN_PICKAXE`)
7. silk touch (+ level if configured), unless `.nosilk`
8. drop chance / permission-based chances (now order-respecting)
9. inventory-full check — **before** charging (was after)
10. charge — **only now** that the spawner is definitely being given (was before
    the chance roll and inventory check, so players could pay for nothing)
11. give item (owned by the miner for soulbound types), delete the DB row

## Placing rules (SpawnerPlaceListener)

`HIGH`, `ignoreCancelled` (so GriefPrevention / Insights / MobFarmManager, which
run earlier, win). Non-owner can't place someone's soulbound spawner. On place,
the spawner type is set and, if the item had an owner, the row is registered.

## Protection

- `EntityExplodeEvent` **and** `BlockExplodeEvent` — spawners removed from the
  block list (`protection.block-explosions`, default true). The old plugin only
  covered `EntityExplodeEvent`; adding `BlockExplodeEvent` also stops bed/anchor
  explosions in the nether/end taking spawners — strictly more protective.
- `EntityChangeBlockEvent` for WITHER / ENDER_DRAGON on a spawner → cancelled
  (`protection.block-wither`, default true).
- Anvil: `PrepareAnvilEvent` result nulled + `InventoryClickEvent` on the result
  slot cancelled with a message.

## Deliberately dropped / changed

| Old | New |
|---|---|
| Spawn-egg spawner retyping | **disabled for everyone** (`SpawnerEggListener`, hardcoded — no config, no permission, no bypass), matching the old plugin's always-cancel behaviour. The old `eggs.*` permission config is not carried over. (Dropped in the 4.x rebuild, restored with a toggle in 4.3.1, made unconditional in 4.3.2.) |
| zAuctionHouse hook | removed (unused; sell-guard covers AxAuctions/ChestShop instead) |
| ShopGUI+ spawner provider | removed (you sell via command, not item) |
| Startup entity-type console dump | removed |
| `/ms` command | removed — `/soulboundspawners` + `/sbs` |
| `mineablespawners.*` perms | `soulboundspawners.*`; old nodes honoured while `legacy-permissions: true` |
| Truncated "broken spawner" join message | silent; unidentifiable items logged at FINE, left untouched |

## Spawn-rate throttle (new)

The dead `performance:` block in the old `newC.yml` becomes real: on
`SpawnerSpawnEvent`, if `online > performance.player-threshold`, cancel each spawn
with `performance.spawn-reduction-percent` probability. All active spawners,
server-wide, `ignoreCancelled` so it never fights MobFarmManager.

## New ownership commands

`/sbs type` / `/sbs transfer` / `/sbs unclaim` act on the spawner you look at
(6-block raycast). `/sbs item …` acts on the spawner in your hand. Retyping a
spawner to a soulbound type makes the actor the owner (then reassign with
`transfer`).

## To verify on staging

Runtime-untested. Check these first:

- `ItemMeta#getAsString()` output format on 26.2 still contains `ms_mob:"…"` for
  a real old MineableSpawners item (the legacy read path). Grab one from a player
  and `/sbs info` after placing.
- SQLite driver present (via `libraries:` or bundled). `/sbs status` → "Storage
  healthy: true".
- `/sbs migrate confirm` against a **copy** of a real `mspawners` table — check
  the reconciliation report numbers against the source row count.
- The `sell-guard` event class names actually match your AxAuctions / ChestShop
  versions (console prints "Watching …" for each one it hooks). Add the real
  AxAuctions listing-event class to `sell-guard.events`.
- `EntityType.WITHER` / `ENDER_DRAGON` enum names unchanged on 26.2.
- `Tag.ITEMS_PICKAXES` covers whatever pickaxe types 26.2 has.

## Test matrix

Run on staging beside a mirror of the current jar, before and after migration.

1. Each soulbound type: owned spawner placed + row present → spawns only with
   owner online within 16 blocks; non-owner can't break; owner mines it back to
   an item with the right data and the row is removed.
2. Re-run after MySQL→SQLite migration — identical.
3. Non-soulbound spawner: unprotected, spawns normally, anyone with silk mines it.
4. Item with `ms_mob` + `ms_owner` in inventory → places as right type + owner.
5. Item with only `ms_mob` from a chest / shulker / ender chest → right type,
   unowned.
6. Legacy item, type in display name → places correctly.
7. Legacy item, type in a lore line → places correctly.
8. `/sbs give`, `/sbs types`, `/sbs reload` — sane output.
9. `/sbs type`, `/sbs transfer`, `/sbs unclaim`, `/sbs info` on a placed spawner.
10. `/sbs item type`, `/sbs item owner <p>`, `/sbs item owner none` on a held one.
11. Explosion + wither next to a spawner → spawner survives.
12. Spawn egg on a spawner (any player, OP included) → type unchanged, egg not consumed
    (a `soulboundspawners.bypass` player can still do it).
13. Anvil rename of a spawner item → blocked.
14. Owner offline / in another world → spawner doesn't spawn, **no console error**.
15. Delete `spawners.db`, restart → degraded mode: protection off, spawns not
    mass-cancelled, `/sbs status` shows it; restore the file + `/sbs reload`.
16. Full restart → every owned spawner still owned.
17. `mineablespawners.bypass` (legacy node) still grants bypass.
18. Soulbound item listed on AxAuctions / ChestShop → blocked with a message.
