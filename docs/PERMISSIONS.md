# Permissions

`default: op` = operators have it automatically (grant to staff via LuckPerms).
`default: false` = nobody has it until granted.

## Gameplay

| Node | Effect | Default |
|---|---|---|
| `soulboundspawners.bypass` | Break or place **any** spawner ignoring ownership. Also enables the "sneak + mine to remove a spawner as admin" path. | op |
| `soulboundspawners.nosilk` | Mine spawners without silk touch. | false |
| `soulboundspawners.mine` | Allowed to mine spawners at all — **only checked when `mining.require-permission: true`**. Your config has it `false`, so this node does nothing right now. | false |
| `soulboundspawners.mine.<type>` | Per-type mining, e.g. `soulboundspawners.mine.witch` — **only checked when `mining.require-individual-permission: true`** (also `false` in your config). Dynamic, not declared. | — |

On your current config a normal player needs **no permission** to mine a spawner
(just silk touch + a pickaxe) or to place one.

## Commands

| Node | Command | Default |
|---|---|---|
| `soulboundspawners.give` | `/sbs give` — checked only if `give.require-permission: true` (default true) | op |
| `soulboundspawners.type` | `/sbs type`, `/sbs item type` | op |
| `soulboundspawners.item` | `/sbs item` (owner sub-commands) | op |
| `soulboundspawners.transfer` | `/sbs transfer` | op |
| `soulboundspawners.unclaim` | `/sbs unclaim` | op |
| `soulboundspawners.info` | `/sbs info` | op |
| `soulboundspawners.types` | `/sbs types` — checked only if `types.require-permission: true` (default true) | op |
| `soulboundspawners.reload` | `/sbs reload` | op |
| `soulboundspawners.migrate` | `/sbs migrate` | op |
| `soulboundspawners.status` | `/sbs status` | op |

`/sbs` with no arguments (the help list) needs no permission — it only shows the
sub-commands the sender can use.

## Umbrella

| Node | Effect | Default |
|---|---|---|
| `soulboundspawners.admin` | Grants every node above. | op |

## Legacy nodes

While `legacy-permissions: true` in config (default), these old MineableSpawners
nodes are also accepted, so LuckPerms can be migrated gradually:

| Old node | Acts as |
|---|---|
| `mineablespawners.bypass` | `soulboundspawners.bypass` |
| `mineablespawners.nosilk` | `soulboundspawners.nosilk` |
| `mineablespawners.mine` | `soulboundspawners.mine` |
| `mineablespawners.mine.<type>` | `soulboundspawners.mine.<type>` |
| `mineablespawners.give` | `soulboundspawners.give` |
| `mineablespawners.set` | `soulboundspawners.type` |
| `mineablespawners.types` | `soulboundspawners.types` |
| `mineablespawners.reload` | `soulboundspawners.reload` |

Set `legacy-permissions: false` once every group has been updated.

## Not plugin permissions

`mining.perm-based-chances` in config lets you gate drop chance behind arbitrary
nodes you invent (the shipped example uses `minechance.1` / `minechance.2`). Only
used when `mining.use-perm-based-chances: true`.
