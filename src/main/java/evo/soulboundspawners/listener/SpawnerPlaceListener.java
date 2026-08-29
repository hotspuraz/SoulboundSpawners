package evo.soulboundspawners.listener;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import evo.soulboundspawners.Text;
import evo.soulboundspawners.config.PluginConfig;
import evo.soulboundspawners.config.Prices;
import evo.soulboundspawners.ownership.BlockKey;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;

import java.text.DecimalFormat;
import java.util.UUID;

public final class SpawnerPlaceListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;
    private final DecimalFormat df = new DecimalFormat("##.##");

    public SpawnerPlaceListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Block block = e.getBlockPlaced();
        if (block.getType() != Material.SPAWNER) return;

        ItemStack hand = e.getItemInHand();
        EntityType type = plugin.spawnerItems().readType(hand);
        if (type == null) return; // vanilla / unrecognised spawner item – leave it alone

        UUID owner = plugin.spawnerItems().readOwner(hand);
        Player player = e.getPlayer();
        PluginConfig cfg = plugin.config();

        if (owner != null && !player.getUniqueId().equals(owner) && !plugin.perms().hasBypass(player)) {
            e.setCancelled(true);
            plugin.notify(player, cfg.msg("not-owner-place")
                    .replace("%owner%", plugin.spawnerItems().nameOf(owner)));
            return;
        }

        boolean bypassing = player.getGameMode() == GameMode.CREATIVE || plugin.perms().hasBypass(player);
        double cost = 0;

        if (!bypassing) {
            if (cfg.placingBlacklistedWorlds().contains(player.getWorld().getName())) {
                e.setCancelled(true);
                plugin.notify(player, cfg.placingMsg("blacklisted"));
                return;
            }
            if (cfg.placingCharge() && plugin.vault().isAvailable()) {
                cost = new Prices(cfg.placingPrices()).priceFor(type);
                if (cost > 0 && !plugin.vault().withdraw(player, cost)) {
                    double missing = cost - plugin.vault().balance(player);
                    e.setCancelled(true);
                    plugin.notify(player, cfg.placingMsg("not-enough-money")
                            .replace("%missing%", df.format(missing)).replace("%cost%", df.format(cost)));
                    return;
                }
            }
        }

        Location loc = block.getLocation();
        CreatureSpawner state = (CreatureSpawner) block.getState();
        state.setSpawnedType(type);
        state.update(true, false);

        if (owner != null) {
            plugin.ownership().register(BlockKey.of(loc), type.name(), owner);
        }
        if (cfg.placingLog()) {
            plugin.getLogger().info("Player " + player.getName() + " placed a "
                    + type.name().toLowerCase() + " spawner at "
                    + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ()
                    + " (" + loc.getWorld().getName() + ")"
                    + (owner != null ? " owner=" + owner : ""));
        }
        if (cost > 0) {
            plugin.notify(player, cfg.placingMsg("transaction-success")
                    .replace("%type%", Text.prettyMob(type.name()))
                    .replace("%cost%", df.format(cost))
                    .replace("%balance%", df.format(plugin.vault().balance(player))));
        }
    }
}
