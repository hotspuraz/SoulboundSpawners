# MySQL → SQLite migration

One-time, one server at a time, **with no players online**.

## What it does

1. Reads `plugins/AtherialLibPlugin/database.yml` for the MySQL connection
   (host, database, credentials) — nothing to type in.
2. `SELECT uuid, location, type, owner FROM mspawners`.
3. Parses each row: `location` → `world,x,y,z`; `owner` → UUID (blank/"null" kept
   as no-owner). Rows with an unparseable location are skipped and listed.
4. De-duplicates on location (same block registered twice → keeps one, counts it).
5. Writes `plugins/SoulboundSpawners/migration-backup-<timestamp>.json` — every
   parsed row, before dedupe.
6. Imports the de-duplicated rows into `spawners.db` in one transaction.
7. Prints a reconciliation report and reloads the ownership cache.

**The MySQL `mspawners` table is only read.** It is left exactly as it was, so a
rollback to the old jar loses nothing (up to the moment of cutover).

## Steps

1. Deploy the jar, start the server once so `config.yml` imports and
   `spawners.db` is created. Check `/sbs status` → `Storage healthy: true`.
2. If it says the SQLite driver is missing: either your Paper build lacks it and
   `libraries:` couldn't download it — drop `sqlite-jdbc.jar` into
   `plugins/SoulboundSpawners/driver/` and restart.
3. Kick everyone / do this during a restart window. `/sbs migrate confirm`.
4. If it reports no MySQL driver: download `mysql-connector-j` (Maven Central)
   and drop the jar into `plugins/SoulboundSpawners/driver/`, then re-run.
5. Check the report:
   - `rows read` should match the source table's row count (check in phpMyAdmin).
   - `imported` = `rows read − duplicates collapsed − bad locations`.
   - `bad owners` should be 0 for your data.
6. `/sbs status` → `Rows in DB` and `Tracked spawners (cache)` should match
   `imported`.
7. Spot-check a few known farms with `/sbs info`.

## Rollback

Before cutover: swap the old `MineableSpawners.jar` + `AtherialLibPlugin` back in.
The MySQL data is untouched.

After cutover, if something's wrong: the same swap-back works — you lose only
ownership changes made between the migration and the rollback (nothing, if done
in a maintenance window). The JSON backup and `spawners.db` are also kept.

## Per server

Each survival server has its own `mspawners` table in its own database, so run
`/sbs migrate confirm` once on each. They don't share data.
