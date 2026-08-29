package evo.soulboundspawners.command;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import evo.soulboundspawners.Text;
import evo.soulboundspawners.ownership.BlockKey;
import evo.soulboundspawners.ownership.OwnedSpawner;
import evo.soulboundspawners.storage.LegacyDatabaseConfig;
import evo.soulboundspawners.storage.MigrationService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

public final class SbsCommand implements CommandExecutor, TabCompleter {

    /**
     * Every entity type, lower-case, for /sbs give|type|item type completion.
     * Living types are listed first (they're what a spawner is normally set to),
     * then the rest - a vanilla spawner will accept any of them.
     */
    private static final List<String> ALL_MOB_TYPES;
    static {
        List<String> living = new ArrayList<>();
        List<String> other = new ArrayList<>();
        for (EntityType t : EntityType.values()) {
            if (t == EntityType.UNKNOWN) continue;
            (t.isAlive() ? living : other).add(t.name().toLowerCase(Locale.ROOT));
        }
        living.sort(null);
        other.sort(null);
        List<String> combined = new ArrayList<>(living);
        combined.addAll(other);
        ALL_MOB_TYPES = List.copyOf(combined);
    }

    private final SoulboundSpawnersPlugin plugin;

    public SbsCommand(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "help" -> help(sender);
                case "reload" -> reload(sender);
                case "status" -> status(sender);
                case "audit" -> audit(sender, args);
                case "types" -> types(sender);
                case "give" -> give(sender, args);
                case "info" -> info(sender);
                case "type" -> type(sender, args);
                case "transfer" -> transfer(sender, args);
                case "unclaim" -> unclaim(sender);
                case "item" -> item(sender, args);
                case "migrate" -> migrate(sender, args);
                default -> help(sender);
            }
        } catch (NumberFormatException e) {
            plugin.send(sender, "&cThat is not a number.");
        }
        return true;
    }

    // --- subcommands ---

    private void help(CommandSender s) {
        plugin.send(s, "&e&lSoulboundSpawners");
        if (perm(s, "type")) {
            plugin.send(s, "&f/sbs type <type> &7- retype the spawner you are looking at");
            plugin.send(s, "&f/sbs item type <type> &7- retype the spawner in your hand");
            plugin.send(s, "&f/sbs item owner <player|none> &7- set/clear the held spawner's owner");
        }
        if (perm(s, "transfer")) plugin.send(s, "&f/sbs transfer <player> &7- change the owner of the spawner you look at");
        if (perm(s, "unclaim")) plugin.send(s, "&f/sbs unclaim &7- remove ownership from the spawner you look at");
        if (perm(s, "info")) plugin.send(s, "&f/sbs info &7- details of the spawner you look at");
        if (perm(s, "give")) plugin.send(s, "&f/sbs give <player> <type> <amount>");
        if (perm(s, "types")) plugin.send(s, "&f/sbs types");
        if (perm(s, "reload")) plugin.send(s, "&f/sbs reload");
        if (perm(s, "migrate")) plugin.send(s, "&f/sbs migrate confirm &7- one-time MySQL import (offline!)");
        if (perm(s, "status")) plugin.send(s, "&f/sbs status");
        if (perm(s, "audit")) plugin.send(s, "&f/sbs audit [full|prune] &7- data health report");
    }

    private void reload(CommandSender s) {
        if (deny(s, "reload")) return;
        plugin.reloadEverything();
        plugin.send(s, plugin.config().msg("reloaded"));
    }

    private void audit(CommandSender s, String[] args) {
        if (deny(s, "audit")) return;
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        Runnable job;
        if (mode.equals("full")) {
            job = () -> AuditRunner.runFull(plugin, s);
        } else if (mode.equals("prune")) {
            if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
                plugin.send(s, "&e/sbs audit prune confirm &7- deletes tracked-spawner rows whose block is");
                plugin.send(s, "&7confirmed gone (chunk loads OK, no spawner there). Writes a backup JSON first.");
                plugin.send(s, "&7Rows in chunks that won't load are left alone. Run &f/sbs audit full&7 to preview.");
                return;
            }
            job = () -> AuditRunner.runPrune(plugin, s);
        } else {
            job = () -> AuditRunner.run(plugin, s);
        }
        plugin.getServer().getScheduler().runTask(plugin, job);
    }

    private void status(CommandSender s) {
        if (deny(s, "status")) return;
        plugin.send(s, "&e&lSoulboundSpawners status");
        plugin.send(s, "&7Storage healthy: &f" + plugin.store().isHealthy());
        plugin.send(s, "&7Degraded mode: &f" + plugin.ownership().isDegraded());
        plugin.send(s, "&7Tracked spawners (cache): &f" + plugin.ownership().size());
        plugin.send(s, "&7Rows in DB: &f" + plugin.store().count());
        plugin.send(s, "&7Vault economy: &f" + plugin.vault().isAvailable());
        plugin.send(s, "&7Insights hook: &f" + plugin.insights().isActive());
        plugin.send(s, "&7MarriageMaster hook: &f" + plugin.marriage().isAvailable());
        plugin.send(s, "&7Soulbound types: &f" + String.join(", ", plugin.soulboundTypes().names()));
    }

    private void types(CommandSender s) {
        if (plugin.config().typesRequirePermission() && deny(s, "types")) return;
        StringBuilder sb = new StringBuilder(plugin.config().typesTitle());
        for (EntityType t : EntityType.values()) {
            if (t == EntityType.UNKNOWN) continue;
            sb.append(plugin.config().typesEntry().replace("%mob%", t.name().toLowerCase(Locale.ROOT)));
        }
        plugin.send(s, sb.toString());
    }

    private void give(CommandSender s, String[] args) {
        if (plugin.config().giveRequirePermission() && deny(s, "give")) return;
        if (args.length != 4) {
            plugin.send(s, "&cUsage: /sbs give <player> <type> <amount>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.send(s, plugin.config().giveMsg("player-does-not-exist"));
            return;
        }
        EntityType type = parseType(args[2]);
        if (type == null) {
            plugin.send(s, plugin.config().giveMsg("invalid-type"));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            plugin.send(s, plugin.config().giveMsg("invalid-amount"));
            return;
        }
        if (amount < 1) amount = 1;

        boolean soulbound = plugin.soulboundTypes().isSoulbound(type);
        ItemStack item = plugin.spawnerItems().create(type, soulbound ? target.getUniqueId() : null, amount);
        var leftover = target.getInventory().addItem(item);
        if (!leftover.isEmpty() && plugin.config().giveDropIfFull()) {
            leftover.values().forEach(rest -> target.getWorld().dropItem(target.getLocation(), rest));
            plugin.send(target, plugin.config().giveMsg("inventory-full"));
        } else if (!leftover.isEmpty()) {
            plugin.send(s, plugin.config().giveMsg("inventory-full"));
        }

        String mob = Text.prettyMob(type.name());
        plugin.send(s, plugin.config().giveMsg("success")
                .replace("%amount%", String.valueOf(amount)).replace("%mob%", mob).replace("%target%", target.getName()));
        plugin.send(target, plugin.config().giveMsg("received")
                .replace("%amount%", String.valueOf(amount)).replace("%mob%", mob));
    }

    private void info(CommandSender s) {
        if (deny(s, "info")) return;
        Player p = player(s);
        if (p == null) return;
        Block b = targetSpawner(p);
        if (b == null) {
            plugin.send(s, plugin.config().msg("not-looking-at-spawner"));
            return;
        }
        BlockKey key = BlockKey.of(b);
        CreatureSpawner st = (CreatureSpawner) b.getState();
        OwnedSpawner owned = plugin.ownership().get(key);
        String mob = st.getSpawnedType() == null ? "Empty" : Text.prettyMob(st.getSpawnedType().name());
        String owner = owned != null && owned.owner() != null
                ? plugin.spawnerItems().nameOf(owned.owner()) : "&7none";
        plugin.send(s, plugin.config().msg("info")
                .replace("%mob%", mob).replace("%owner%", owner)
                .replace("%tracked%", String.valueOf(owned != null)));
    }

    private void type(CommandSender s, String[] args) {
        if (deny(s, "type")) return;
        Player p = player(s);
        if (p == null) return;
        if (args.length != 2) {
            plugin.send(s, "&cUsage: /sbs type <type>");
            return;
        }
        EntityType type = parseType(args[1]);
        if (type == null) {
            plugin.send(s, plugin.config().msg("invalid-type"));
            return;
        }
        Block b = targetSpawner(p);
        if (b == null) {
            plugin.send(s, plugin.config().msg("not-looking-at-spawner"));
            return;
        }
        CreatureSpawner st = (CreatureSpawner) b.getState();
        EntityType from = st.getSpawnedType();
        st.setSpawnedType(type);
        st.update(true, false);

        BlockKey key = BlockKey.of(b);
        if (plugin.soulboundTypes().isSoulbound(type)) {
            if (plugin.ownership().isTracked(key)) {
                plugin.ownership().setType(key, type.name());
            } else {
                // holder becomes the owner when retyping to a soulbound type
                plugin.ownership().register(key, type.name(), p.getUniqueId());
            }
        } else if (plugin.ownership().isTracked(key)) {
            plugin.ownership().unregister(key);
        }
        plugin.send(s, plugin.config().msg("spawner-retyped")
                .replace("%from%", from == null ? "Empty" : Text.prettyMob(from.name()))
                .replace("%to%", Text.prettyMob(type.name())));
    }

    private void transfer(CommandSender s, String[] args) {
        if (deny(s, "transfer")) return;
        Player p = player(s);
        if (p == null) return;
        if (args.length != 2) {
            plugin.send(s, "&cUsage: /sbs transfer <player>");
            return;
        }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            plugin.send(s, plugin.config().msg("player-not-found"));
            return;
        }
        Block b = targetSpawner(p);
        if (b == null) {
            plugin.send(s, plugin.config().msg("not-looking-at-spawner"));
            return;
        }
        BlockKey key = BlockKey.of(b);
        CreatureSpawner st = (CreatureSpawner) b.getState();
        EntityType type = st.getSpawnedType();
        if (!plugin.ownership().isTracked(key)) {
            plugin.ownership().register(key, type == null ? null : type.name(), target.getUniqueId());
        } else if (!plugin.ownership().setOwner(key, target.getUniqueId())) {
            plugin.send(s, "&cCould not update ownership (storage degraded?).");
            return;
        }
        plugin.send(s, plugin.config().msg("transferred").replace("%player%", String.valueOf(target.getName())));
    }

    private void unclaim(CommandSender s) {
        if (deny(s, "unclaim")) return;
        Player p = player(s);
        if (p == null) return;
        Block b = targetSpawner(p);
        if (b == null) {
            plugin.send(s, plugin.config().msg("not-looking-at-spawner"));
            return;
        }
        plugin.ownership().unregister(BlockKey.of(b));
        plugin.send(s, plugin.config().msg("unclaimed"));
    }

    private void item(CommandSender s, String[] args) {
        if (deny(s, "item") && deny(s, "type")) return;
        Player p = player(s);
        if (p == null) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType() != Material.SPAWNER) {
            plugin.send(s, plugin.config().msg("not-holding-spawner"));
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("type")) {
            if (args.length != 3) {
                plugin.send(s, "&cUsage: /sbs item type <type>");
                return;
            }
            EntityType type = parseType(args[2]);
            if (type == null) {
                plugin.send(s, plugin.config().msg("invalid-type"));
                return;
            }
            UUID owner = plugin.soulboundTypes().isSoulbound(type) ? p.getUniqueId() : null;
            p.getInventory().setItemInMainHand(plugin.spawnerItems().create(type, owner, hand.getAmount()));
            plugin.send(s, plugin.config().msg("item-retyped").replace("%mob%", Text.prettyMob(type.name())));
        } else if (args.length >= 2 && args[1].equalsIgnoreCase("owner")) {
            if (args.length != 3) {
                plugin.send(s, "&cUsage: /sbs item owner <player|none>");
                return;
            }
            if (args[2].equalsIgnoreCase("none")) {
                p.getInventory().setItemInMainHand(plugin.spawnerItems().withOwner(hand, null));
                plugin.send(s, plugin.config().msg("item-owner-cleared"));
                return;
            }
            OfflinePlayer target = resolvePlayer(args[2]);
            if (target == null) {
                plugin.send(s, plugin.config().msg("player-not-found"));
                return;
            }
            p.getInventory().setItemInMainHand(plugin.spawnerItems().withOwner(hand, target.getUniqueId()));
            plugin.send(s, plugin.config().msg("item-owner-set").replace("%player%", String.valueOf(target.getName())));
        } else {
            plugin.send(s, "&cUsage: /sbs item <type|owner> ...");
        }
    }

    private void migrate(CommandSender s, String[] args) {
        if (deny(s, "migrate")) return;
        LegacyDatabaseConfig cfg;
        if (plugin.config().migrationOverride()) {
            cfg = LegacyDatabaseConfig.explicit(
                    plugin.config().migrationHost(), plugin.config().migrationPort(),
                    plugin.config().migrationDatabase(), plugin.config().migrationUsername(),
                    plugin.config().migrationPassword(), plugin.config().migrationTable());
        } else {
            cfg = LegacyDatabaseConfig.discover(plugin.pluginsFolder());
        }

        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            plugin.send(s, "&e/sbs migrate confirm &7- imports the old 'mspawners' MySQL table into SQLite.");
            plugin.send(s, "&7Source: &f" + (cfg == null ? "&cnot found - set the migration: section in config.yml" : cfg.source()));
            plugin.send(s, "&cRun this with NO players online. The MySQL table is only read, never changed.");
            plugin.send(s, "&7A JSON backup and a reconciliation report are written to plugins/SoulboundSpawners/.");
            return;
        }
        if (cfg == null) {
            plugin.send(s, "&cNo migration source. Either put plugins/AtherialLibPlugin/database.yml in place, "
                    + "or fill in the migration: section of config.yml.");
            return;
        }
        if (!plugin.store().isHealthy()) {
            plugin.send(s, "&cSQLite store is not healthy – fix that first (see console).");
            return;
        }
        plugin.send(s, "&eStarting migration from &f" + cfg.source() + "&e ... watch the console.");
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                MigrationService svc = new MigrationService(plugin.store(), plugin.getDataFolder());
                MigrationService.Report r = svc.run(cfg);
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    plugin.send(s, "&a&lMigration complete.");
                    plugin.send(s, "&7rows read: &f" + r.rowsRead()
                            + " &7imported: &f" + r.imported()
                            + " &7duplicates collapsed: &f" + r.deduped()
                            + " &7bad locations: &f" + r.badLocation()
                            + " &7bad owners: &f" + r.badOwner());
                    plugin.send(s, "&7backup: &f" + r.backupFile().getName());
                    plugin.getLogger().info("[migrate] " + r);
                    r.notes().forEach(n -> plugin.getLogger().info("[migrate] " + n));
                    plugin.reloadEverything();
                    plugin.send(s, "&aOwnership cache reloaded from SQLite (" + plugin.ownership().size() + " spawners).");
                });
            } catch (Exception ex) {
                plugin.getLogger().severe("[migrate] failed: " + ex);
                ex.printStackTrace();
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        plugin.send(s, "&cMigration failed: " + ex.getMessage() + " (see console)"));
            }
        });
    }

    // --- helpers ---

    private boolean perm(CommandSender s, String node) {
        return plugin.perms().has(s, node) || plugin.perms().has(s, "admin");
    }

    /** @return true if the sender is NOT allowed (message already sent). */
    private boolean deny(CommandSender s, String node) {
        if (perm(s, node)) return false;
        plugin.send(s, plugin.config().msg("no-permission"));
        return true;
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        plugin.send(s, plugin.config().msg("not-a-player"));
        return null;
    }

    private Block targetSpawner(Player p) {
        Block b = p.getTargetBlockExact(6);
        return (b != null && b.getType() == Material.SPAWNER) ? b : null;
    }

    private EntityType parseType(String s) {
        try {
            EntityType t = EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT));
            return t == EntityType.UNKNOWN ? null : t;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off != null) return off;
        @SuppressWarnings("deprecation")
        OfflinePlayer legacy = Bukkit.getOfflinePlayer(name);
        return legacy.hasPlayedBefore() ? legacy : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(Arrays.asList("help", "type", "transfer", "unclaim", "info", "item", "give", "types", "reload", "status", "audit", "migrate"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("audit")) return filter(List.of("full", "prune"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("audit") && args[1].equalsIgnoreCase("prune")) return filter(List.of("confirm"), args[2]);
        if (args.length == 2 && args[0].equalsIgnoreCase("item")) {
            return filter(Arrays.asList("type", "owner"), args[1]);
        }
        boolean typeArg = (args.length == 2 && (args[0].equalsIgnoreCase("type")))
                || (args.length == 3 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("type"))
                || (args.length == 3 && args[0].equalsIgnoreCase("give"));
        if (typeArg) {
            String tok = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String s : ALL_MOB_TYPES) {
                if (s.startsWith(tok)) out.add(s);
            }
            return out;
        }
        if ((args.length == 2 && (args[0].equalsIgnoreCase("transfer") || args[0].equalsIgnoreCase("give")))
                || (args.length == 3 && args[0].equalsIgnoreCase("item") && args[1].equalsIgnoreCase("owner"))) {
            return null; // let Bukkit suggest player names
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String token) {
        String t = token.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(t)).collect(Collectors.toList());
    }
}
