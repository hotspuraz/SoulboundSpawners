# Production rollout runbook

Do this **one server at a time**, starting with the lowest-traffic survival
world. Don't do the whole network in one night.

## Before you touch any production server

- [ ] SoulboundSpawners has passed Tier 1–3 on a full-stack staging clone
      (see `TESTING.md`).
- [ ] You have the current `MineableSpawners.jar` and `AtherialLibPlugin.jar`
      archived somewhere you can grab them fast (rollback).
- [ ] You know, per server, whether **anything other than MineableSpawners** uses
      AtherialLibPlugin (minigames, custom Imevo plugins). If yes, AtherialLibPlugin
      stays; if no, it can be removed in the last step.
- [ ] Decide the world order. First one should be quiet.

## Per server

### 1. Stop

```
save-all
stop
```

Take a **full server backup** (world + `plugins/`) before proceeding.

### 2. Swap the jar

- Remove `plugins/MineableSpawners.jar`.
- Add `plugins/SoulboundSpawners-<version>.jar`.
- Leave `AtherialLibPlugin.jar` in place for now.
- Put your production `plugins/MineableSpawners/config.yml` + `newC.yml` where the
  import can find them — they're already there, just don't delete the folder yet.

### 3. Start (no players)

Keep the server in **whitelist / maintenance mode**. On boot, check console:

- [ ] `SoulboundSpawners <version> enabled – 0 tracked spawners, 10 soulbound types.`
- [ ] no stack traces
- [ ] `[sell-guard] Watching …` lines for AxAuctions / ChestShop
- [ ] `/sbs status` → `Storage healthy: true`, `Degraded mode: false`

Review `plugins/SoulboundSpawners/config.yml` — spot-check the imported values
(soulbound list = your 10, `spawn-distance: 16`, `backwards-compatibility: true`,
`mining.require-permission` as you want it, display name/lore).

### 4. Migrate

```
/sbs migrate confirm
```

It auto-discovers this server's MySQL from `AtherialLibPlugin/database.yml`.
Read the report:

- [ ] `rows read` == the `mspawners` row count for this server (check phpMyAdmin)
- [ ] `bad locations` and `bad owners` are 0 (or you understand why not)
- [ ] `imported` == `rows read − duplicates collapsed`
- [ ] `/sbs status` → `Rows in DB` and `Tracked spawners (cache)` == `imported`
- [ ] `plugins/SoulboundSpawners/migration-backup-*.json` written — **archive it
      off-server**

### 5. Audit

```
/sbs audit
```

- [ ] `cache == db`, no MISMATCH
- [ ] `null owner`, `invalid type`, `unknown world` all 0 (investigate any that aren't)
- [ ] fly to 2–3 known farms, `/sbs info` each → right owner; stand next to one as
      the owner → it spawns; walk >16 blocks away → it stops

### 6. Open up

- Remove whitelist / maintenance mode.
- Watch chat and console for the first hour. Things to look for:
  - players reporting spawners "not working" or "gone"
  - `[SoulboundSpawners]` warnings/errors in console
  - `/sbs audit` again after players have loaded chunks around their bases —
    `block gone` should stay near 0

### 7. Rollback (if needed, within the first day or two)

```
stop
```

- Swap `SoulboundSpawners.jar` out, `MineableSpawners.jar` back in.
- Keep `AtherialLibPlugin.jar`.
- The MySQL `mspawners` table was only read, so it's intact — the old plugin
  picks up exactly where it left off. Any ownership changes made after cutover
  are lost (nothing, if the window was short).
- Start.

### 8. Decommission (weeks later, once confident)

- `/sbs audit` clean, no player complaints, several weeks in.
- If nothing else on this server uses AtherialLibPlugin: remove
  `AtherialLibPlugin.jar` and its folder.
- Keep the MySQL `s<n>_Soulbound` database as a cold backup indefinitely
  (or export it once and drop it).
- Archive the migration JSON permanently.

## After all servers are done

- Set `legacy-permissions: false` in every config once LuckPerms groups use the
  `soulboundspawners.*` nodes.
- Delete the `plugins/MineableSpawners/` folders.
- Tag the SoulboundSpawners release you settled on.
