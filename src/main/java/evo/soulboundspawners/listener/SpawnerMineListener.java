package evo.soulboundspawners.listener;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import evo.soulboundspawners.Text;
import evo.soulboundspawners.config.PluginConfig;
import evo.soulboundspawners.config.Prices;
import evo.soulboundspawners.ownership.BlockKey;
import evo.soulboundspawners.ownership.OwnedSpawner;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.text.DecimalFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Silk-touch spawner mining. Ported from the deployed MineableSpawners
 * behaviour, with the fixes from the audit:
 * <ul>
 *   <li>runs at HIGH, not MONITOR, so cancelling is legal and other plugins see
 *       a consistent final state;</li>
 *   <li>money is only taken once the spawner is actually going to be given;</li>
 *   <li>permission-based chances respect config order.</li>
 * </ul>
 */
public final class SpawnerMineListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;
    private final Set<BlockKey> recentlyMined = new HashSet<>();
    private final DecimalFormat df = new DecimalFormat("##.##");

    public SpawnerMineListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Block block = e.getBlock();
        if (block.getType() != Material.SPAWNER) return;

        PluginConfig cfg = plugin.config();
        Player player = e.getPlayer();
        Location loc = block.getLocation();
        BlockKey key = BlockKey.of(loc);
        CreatureSpawner state = (CreatureSpawner) block.getState();
        EntityType entityType = state.getSpawnedType();

        OwnedSpawner owned = plugin.ownership().get(key);
        boolean degraded = plugin.ownership().isDegraded();
        boolean bypassing = player.getGameMode() == GameMode.CREATIVE || plugin.perms().hasBypass(player);

        // ownership protection
        if (owned != null && !degraded && owned.owner() != null
                && !owned.owner().equals(player.getUniqueId()) && !bypassing) {
            plugin.send(player, plugin.config().msg("not-owner-break"));
            e.setCancelled(true);
            return;
        }

        // exp
        if (!cfg.miningDropExp() || recentlyMined.contains(key)) {
            e.setExpToDrop(0);
        }

        // admin bypass path
        if (bypassing) {
            if (!player.isSneaking()) {
                e.setCancelled(true);
                plugin.send(player, cfg.msg("bypass-must-sneak"));
                return;
            }
            OwnedSpawner removed = plugin.ownership().unregister(key);
            plugin.send(player, removed == null ? cfg.msg("bypassed-unbound") : cfg.msg("bypassed"));
            giveSpawner(e, entityType, loc, player, block, 0,
                    removed != null ? removed.owner() : null);
            return;
        }

        // blacklisted world
        if (cfg.miningBlacklistedWorlds().contains(player.getWorld().getName())) {
            plugin.send(player, cfg.miningMsg("blacklisted"));
            e.setCancelled(true);
            return;
        }

        // permission gates
        if (cfg.miningRequirePermission() && !plugin.perms().has(player, "mine")) {
            handleStillBreak(e, key, player, cfg.miningMsg("no-permission"), cfg.miningRequirement("permission"));
            return;
        }
        String typeName = entityType == null ? "" : entityType.name().toLowerCase(Locale.ROOT);
        if (cfg.miningRequireIndividualPermission() && !plugin.perms().hasTyped(player, "mine", typeName)) {
            handleStillBreak(e, key, player, cfg.miningMsg("no-individual-permission"), cfg.miningRequirement("individual-permission"));
            return;
        }

        // tool
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!isAllowedTool(inHand.getType(), cfg)) {
            handleStillBreak(e, key, player, cfg.miningMsg("wrong-tool"), cfg.miningRequirement("wrong-tool"));
            return;
        }

        // silk touch
        if (cfg.miningRequireSilktouch() && !plugin.perms().has(player, "nosilk")) {
            int silk = inHand.getEnchantmentLevel(Enchantment.SILK_TOUCH);
            if (cfg.miningRequireSilktouchLevel()) {
                if (silk < cfg.miningRequiredLevel()) {
                    handleStillBreak(e, key, player,
                            cfg.miningMsg("not-level-required").replace("%level%", String.valueOf(cfg.miningRequiredLevel())),
                            cfg.miningRequirement("silktouch-level").replace("%level%", String.valueOf(cfg.miningRequiredLevel())));
                    return;
                }
            } else if (silk < 1) {
                handleStillBreak(e, key, player, cfg.miningMsg("no-silktouch"), cfg.miningRequirement("silktouch"));
                return;
            }
        }

        // drop chance
        double dropChance = 1.0;
        if (cfg.miningUsePermChances()) {
            Map<String, Double> chances = cfg.miningPermChances();
            for (Map.Entry<String, Double> entry : chances.entrySet()) {
                if (player.hasPermission(entry.getKey())) {
                    dropChance = entry.getValue() / 100.0;
                    break;
                }
            }
        } else {
            dropChance = cfg.miningChance() / 100.0;
        }
        if (dropChance < 1.0 && ThreadLocalRandom.current().nextDouble() >= dropChance) {
            plugin.send(player, cfg.miningMsg("out-of-luck"));
            plugin.ownership().unregister(key); // spawner block still breaks; drop its data
            rememberMined(key);
            return;
        }

        // inventory full (checked BEFORE charging)
        if (cfg.miningDropToInventory() && player.getInventory().firstEmpty() == -1) {
            e.setCancelled(true);
            plugin.send(player, cfg.miningMsg("inventory-full"));
            return;
        }

        // charge (only now that we know the player gets the spawner)
        double cost = 0;
        if (cfg.miningCharge() && plugin.vault().isAvailable()) {
            cost = new Prices(cfg.miningPrices()).priceFor(entityType);
            if (cost > 0 && !plugin.vault().withdraw(player, cost)) {
                double missing = cost - plugin.vault().balance(player);
                plugin.send(player, cfg.miningMsg("not-enough-money")
                        .replace("%missing%", df.format(missing)).replace("%cost%", df.format(cost)));
                e.setCancelled(true);
                return;
            }
        }

        plugin.ownership().unregister(key);
        giveSpawner(e, entityType, loc, player, block, cost, player.getUniqueId());
    }

    private void giveSpawner(BlockBreakEvent e, EntityType type, Location loc, Player player, Block block,
                             double cost, java.util.UUID owner) {
        ItemStack item = plugin.spawnerItems().create(type, owner, 1);
        PluginConfig cfg = plugin.config();

        if (cost > 0) {
            plugin.send(player, cfg.miningMsg("transaction-success")
                    .replace("%type%", Text.prettyMob(type == null ? "" : type.name()))
                    .replace("%cost%", df.format(cost))
                    .replace("%balance%", df.format(plugin.vault().balance(player))));
        }

        rememberMined(BlockKey.of(loc));
        if (cfg.miningDropToInventory()) {
            player.getInventory().addItem(item);
        } else {
            loc.getWorld().dropItemNaturally(loc.toBlockLocation().add(0.5, 0.5, 0.5), item);
        }
    }

    private void handleStillBreak(BlockBreakEvent e, BlockKey key, Player player, String msg, String requirement) {
        if (!plugin.config().miningStillBreak()) {
            e.setCancelled(true);
            if (msg != null && !msg.isEmpty()) plugin.send(player, msg);
            return;
        }
        plugin.ownership().unregister(key);
        String still = plugin.config().miningMsg("still-break");
        if (still != null && !still.isEmpty()) {
            plugin.send(player, still.replace("%requirement%", requirement == null ? "" : requirement));
        }
    }

    private boolean isAllowedTool(Material tool, PluginConfig cfg) {
        if (cfg.miningAnyPickaxe() && Tag.ITEMS_PICKAXES.isTagged(tool)) return true;
        for (String t : cfg.miningTools()) {
            if (t == null) continue;
            String n = t.trim().toUpperCase(Locale.ROOT);
            if (n.equals(tool.name())) return true;
            if (n.equals("GOLD_PICKAXE") && tool == Material.GOLDEN_PICKAXE) return true;
        }
        return false;
    }

    private void rememberMined(BlockKey key) {
        if (recentlyMined.size() > 20_000) recentlyMined.clear();
        recentlyMined.add(key);
    }

    public void clearCache() {
        recentlyMined.clear();
    }
}
