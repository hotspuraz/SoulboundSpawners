# Setting up a test server

Three tiers. Do Tier 1 first — it needs almost nothing and tells you whether the
plugin loads and works at all on your target version.

---

## Tier 1 — does it load and work (30 minutes, no other plugins)

### Server

- **Paper 26.2** if you can (the deployment target — catches any API break now).
  Otherwise Paper 1.21.11, which the jar is built against.
- Java 25 for 26.2, Java 21 for 1.21.11.
- **Internet on first start** (Paper downloads the SQLite driver once via
  `plugin.yml libraries:`). If the box has no internet: download
  `sqlite-jdbc-<ver>.jar` from Maven Central and drop it in
  `plugins/SoulboundSpawners/driver/` before starting.

### Plugins

- `SoulboundSpawners-4.0.0.jar` — **that's it.** No hard dependencies.

### Files it creates on first start

```
plugins/SoulboundSpawners/
  config.yml        (from the bundled defaults; import runs only if
                     plugins/MineableSpawners/ exists — see Tier 2)
  spawners.db       (empty SQLite database)
  .imported         (marker; only appears after a config import)
```

### Steps

1. **Start.** Console should show, with no stack traces:
   `SoulboundSpawners 4.0.0 enabled – 0 tracked spawners, 10 soulbound types.`
2. `/sbs status` → `Storage healthy: true`, `Degraded mode: false`.
   *If it says degraded / not healthy → the SQLite driver isn't available; see above.*
3. Get a silk-touch pickaxe:
   - 26.2: `/give @s diamond_pickaxe[enchantments={"minecraft:silk_touch":1}]`
   - 1.21.x: `/give @s diamond_pickaxe{Enchantments:[{id:"minecraft:silk_touch",lvl:1}]}`
4. `/sbs give <you> witch 1` → you get a **[Witch Spawner]** item with an
   "Owner: <you>" lore line.
5. Place it → it becomes a witch spawner. Stand within 16 blocks → witches spawn.
   Walk >16 blocks away (or `/sbs status` from another player who isn't the
   owner) → spawning stops. Come back → resumes.
6. `/sbs info` while looking at it → shows Witch / your name / Tracked: true.
7. Break it with the silk pickaxe → you get the item back, `/sbs info` on the air
   is gone, `/sbs status` shows `Tracked spawners: 0`.
8. `/sbs give <you> pig 1` (pig isn't soulbound) → plain spawner item, no owner
   line; place it → spawns pigs regardless of where you stand.
9. Place a witch spawner again, then **restart the server**. `/sbs status` should
   still show 1 tracked spawner (persistence works).
10. `/sbs transfer <someone>`, `/sbs unclaim`, `/sbs item type ghast`,
    `/sbs item owner <someone>`, `/sbs item owner none`, `/sbs types`,
    `/sbs reload` — each should give sensible feedback.

If all of that works, the core is sound. If step 4's item doesn't read back
correctly, or step 1 throws, stop and send me the console.

---

## Tier 2 — the compatibility contract (needs real data + a real old item)

This is what actually matters for "don't break anything". You need:

### Files

- **`plugins/AtherialLibPlugin/database.yml`** — copied from a production server.
  `/sbs migrate` reads this file for the MySQL connection. The plugin doesn't
  need to be running, just the file present. (Copy the whole
  `plugins/AtherialLibPlugin/` folder if it's easier.)

### A real legacy spawner item

Pick one:

- **Easiest:** on a production (or staging) server still running the old stack,
  `/ms give <you> witch 1`, put it in a chest, copy the world to the test server,
  take it out of the chest there.
- Or run **the old `MineableSpawners.jar` + `AtherialLibPlugin.jar`** on the test
  server first, `/ms give` yourself several types, drop them in a chest, stop the
  server, remove those two jars, add `SoulboundSpawners.jar`, start again.

Then:

- Log in → the join-fixer should quietly rewrite those items (with
  `global.debug: true` in config it logs how many).
- Take each from the chest, place it → correct mob, and for a soulbound type it
  should be owned by whoever `ms_owner` said.
- `/sbs info` on each placed one.

### Migration

- Point a **test MySQL** at a copy of a real `mspawners` table (phpMyAdmin
  export → import), and set `plugins/AtherialLibPlugin/database.yml` to point at
  it. **Never point it at the live database** for testing — though it only ever
  reads, keep the blast radius zero.
- `/sbs migrate confirm` → check the report: `rows read` matches the table,
  `duplicates collapsed` ~9 (for the s5 data), `bad owners` 0.
- `/sbs status` afterwards: `Rows in DB` and `Tracked spawners` match `imported`.
- Load the matching world and `/sbs info` a few known farms.

### Config import

- Copy `plugins/MineableSpawners/config.yml` and `newC.yml` from production into
  a `plugins/MineableSpawners/` folder on the test server **before first start**.
- Delete `plugins/SoulboundSpawners/` so it's a clean first run.
- Start → console logs the import. Diff the resulting
  `plugins/SoulboundSpawners/config.yml` against expectations: soulbound list =
  your 10 types, `spawn-distance: 16`, `backwards-compatibility: true`,
  `mining.require-permission: false`, your custom display name/lore.

---

## Tier 3 — realistic staging (mirror of production)

Add the plugins whose behaviour overlaps ours, and re-walk the
[BEHAVIOUR.md test matrix](BEHAVIOUR.md#test-matrix):

| Plugin | What to check |
|---|---|
| **GriefPrevention** (+ GPFlags) | Break/place a spawner inside someone else's claim → GP still blocks it (we run at HIGH, after GP). Owner can break their own. |
| **CoreProtect** | `/co inspect` a mined spawner → the log matches what actually happened (this is why we moved off `MONITOR`). |
| **MobFarmManager** | A busy farm → MFM still culls over-limit entities; our throttle only slows the spawn rate. No console errors from either. |
| **Insights** | Placing a 5th spawner in a chunk → Insights blocks it (not us). |
| **AxAuctions**, **ChestShop** | Try to list/sell an owned spawner item → blocked with the "cannot sell soulbound" message. Console shows `[sell-guard] Watching …` for each on startup — **tell me any that don't appear** so I can fix the event class name. |
| **CMI** | Economy present (turn `mining.charge` on briefly to test), and CMI's own spawner handling stays disabled. |
| **LuckPerms** | Grant a test account only `mineablespawners.bypass` (the *old* node) → it should still bypass while `legacy-permissions: true`. |
| **Multiverse-Core** | Owned spawners in `world_nether` / `world_the_end` behave the same as in `world`. |

Run this beside a second server still on the old jar and compare outcomes
step-by-step.
