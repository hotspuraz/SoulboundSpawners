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
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
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
 * behaviour, with the audit fixes:
 * <ul>
 *   <li>runs at HIGH, not MONITOR;</li>
 *   <li>money is only taken once the spawner is actually going to be given;</li>
 *   <li>permission-based chances respect config order.</li>
 * </ul>
 *
 * <p>When it decides the player gets the spawner it <b>takes over the break</b>:
 * cancels the event and removes the block itself. The old plugin relied on the
 * vanilla break proceeding, which fails on servers that protect spawner blocks
 * (region flags, anticheat, etc).
 */
public final class SpawnerMineListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;
    private final Set<BlockKey> recentlyMined = new HashSet<>();
    private final DecimalFormat df = new DecimalFormat("##.##");

    public SpawnerMineListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGH)
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

        if (cfg.debug()) {
            plugin.getLogger().info("[mine] " + key.toLegacyString() + " by " + player.getName()
                    + " cancelledOnEntry=" + e.isCancelled() + " bypassing=" + bypassing
                    + " sneaking=" + player.isSneaking() + " type=" + entityType
                    + " tracked=" + (owned != null) + " degraded=" + degraded);
        }

        // Something earlier (protection plugin) already vetoed the break – respect it.
        if (e.isCancelled()) return;

        // admin bypass path – skips every check below
        if (bypassing) {
            if (!player.isSneaking()) {
                e.setCancelled(true);
                plugin.notify(player, cfg.msg("bypass-must-sneak"));
                return;
            }
            OwnedSpawner removed = plugin.ownership().unregister(key);
            plugin.send(player, removed == null ? cfg.msg("bypassed-unbound") : cfg.msg("bypassed"));
            giveSpawner(e, entityType, loc, player, block, key, 0,
                    removed != null ? removed.owner() : null);
            return;
        }

        // 1. do they have the permission to mine spawners at all?
        if (cfg.miningRequirePermission() && !plugin.perms().has(player, "mine")) {
            e.setCancelled(true);
            plugin.notify(player, cfg.miningMsg("no-permission"));
            return;
        }

        // 2. is this spawner owned by someone else?
        if (owned != null && !degraded && owned.owner() != null
                && !owned.owner().equals(player.getUniqueId())) {
            e.setCancelled(true);
            plugin.send(player, cfg.msg("not-owner-break")
                    .replace("%owner%", plugin.spawnerItems().nameOf(owned.owner())));
            return;
        }

        // blacklisted world
        if (cfg.miningBlacklistedWorlds().contains(player.getWorld().getName())) {
            plugin.notify(player, cfg.miningMsg("blacklisted"));
            e.setCancelled(true);
            return;
        }

        String typeName = entityType == null ? "" : entityType.name().toLowerCase(Locale.ROOT);
        if (cfg.miningRequireIndividualPermission() && !plugin.perms().hasTyped(player, "mine", typeName)) {
            handleStillBreak(e, block, key, player, cfg.miningMsg("no-individual-permission"), cfg.miningRequirement("individual-permission"));
            return;
        }

        // tool
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (!isAllowedTool(inHand.getType(), cfg)) {
            handleStillBreak(e, block, key, player, cfg.miningMsg("wrong-tool"), cfg.miningRequirement("wrong-tool"));
            return;
        }

        // silk touch
        if (cfg.miningRequireSilktouch() && !plugin.perms().has(player, "nosilk")) {
            int silk = inHand.getEnchantmentLevel(Enchantment.SILK_TOUCH);
            if (cfg.miningRequireSilktouchLevel()) {
                if (silk < cfg.miningRequiredLevel()) {
                    handleStillBreak(e, block, key, player,
                            cfg.miningMsg("not-level-required").replace("%level%", String.valueOf(cfg.miningRequiredLevel())),
                            cfg.miningRequirement("silktouch-level").replace("%level%", String.valueOf(cfg.miningRequiredLevel())));
                    return;
                }
            } else if (silk < 1) {
                handleStillBreak(e, block, key, player, cfg.miningMsg("no-silktouch"), cfg.miningRequirement("silktouch"));
                return;
            }
        }

        // drop chance
        double dropChance = 1.0;
        if (cfg.miningUsePermChances()) {
            for (Map.Entry<String, Double> entry : cfg.miningPermChances().entrySet()) {
                if (player.hasPermission(entry.getKey())) {
                    dropChance = entry.getValue() / 100.0;
                    break;
                }
            }
        } else {
            dropChance = cfg.miningChance() / 100.0;
        }
        if (dropChance < 1.0 && ThreadLocalRandom.current().nextDouble() >= dropChance) {
            plugin.notify(player, cfg.miningMsg("out-of-luck"));
            plugin.ownership().unregister(key);
            takeOverBreak(e, block, key, false); // block goes, no item
            return;
        }

        // inventory full (checked BEFORE charging)
        if (cfg.miningDropToInventory() && player.getInventory().firstEmpty() == -1) {
            e.setCancelled(true);
            plugin.notify(player, cfg.miningMsg("inventory-full"));
            return;
        }

        // charge (only now that we know the player gets the spawner)
        double cost = 0;
        if (cfg.miningCharge() && plugin.vault().isAvailable()) {
            cost = new Prices(cfg.miningPrices()).priceFor(entityType);
            if (cost > 0 && !plugin.vault().withdraw(player, cost)) {
                double missing = cost - plugin.vault().balance(player);
                plugin.notify(player, cfg.miningMsg("not-enough-money")
                        .replace("%missing%", df.format(missing)).replace("%cost%", df.format(cost)));
                e.setCancelled(true);
                return;
            }
        }

        plugin.ownership().unregister(key);
        giveSpawner(e, entityType, loc, player, block, key, cost, player.getUniqueId());
    }

    private void giveSpawner(BlockBreakEvent e, EntityType type, Location loc, Player player, Block block,
                             BlockKey key, double cost, java.util.UUID owner) {
        ItemStack item = plugin.spawnerItems().create(type, owner, 1);
        PluginConfig cfg = plugin.config();

        if (cost > 0) {
            plugin.send(player, cfg.miningMsg("transaction-success")
                    .replace("%type%", Text.prettyMob(type == null ? "" : type.name()))
                    .replace("%cost%", df.format(cost))
                    .replace("%balance%", df.format(plugin.vault().balance(player))));
        }

        boolean giveExp = cfg.miningDropExp() && !recentlyMined.contains(key);
        takeOverBreak(e, block, key, giveExp);

        if (cfg.miningDropToInventory() && player.getInventory().firstEmpty() != -1) {
            player.getInventory().addItem(item);
        } else {
            loc.getWorld().dropItemNaturally(loc.toCenterLocation(), item);
        }
    }

    /**
     * Cancel the vanilla break and remove the block ourselves, so it works even
     * where a region flag / anticheat would otherwise stop it.
     */
    private void takeOverBreak(BlockBreakEvent e, Block block, BlockKey key, boolean dropExp) {
        e.setCancelled(true);
        Location center = block.getLocation().toCenterLocation();
        block.setType(Material.AIR);
        block.getWorld().playSound(center, Sound.BLOCK_METAL_BREAK, 1f, 0.8f);
        if (dropExp) {
            int amount = 15 + ThreadLocalRandom.current().nextInt(30) + ThreadLocalRandom.current().nextInt(15);
            block.getWorld().spawn(center, ExperienceOrb.class, orb -> orb.setExperience(amount));
        }
        rememberMined(key);
    }

    private void handleStillBreak(BlockBreakEvent e, Block block, BlockKey key, Player player, String msg, String requirement) {
        if (plugin.config().debug()) {
            plugin.getLogger().info("[mine] blocked " + key.toLegacyString() + " for " + player.getName()
                    + " reason=\"" + requirement + "\"");
        }
        if (!plugin.config().miningStillBreak()) {
            e.setCancelled(true);
            if (msg != null && !msg.isEmpty()) plugin.notify(player, msg);
            return;
        }
        plugin.ownership().unregister(key);
        takeOverBreak(e, block, key, false);
        String still = plugin.config().miningMsg("still-break");
        if (still != null && !still.isEmpty()) {
            plugin.notify(player, still.replace("%requirement%", requirement == null ? "" : requirement));
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
