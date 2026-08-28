package evo.soulboundspawners.listener;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import evo.soulboundspawners.ownership.BlockKey;
import evo.soulboundspawners.ownership.OwnedSpawner;
import evo.soulboundspawners.ownership.OwnershipService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.SpawnerSpawnEvent;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Two jobs, in order:
 * <ol>
 *   <li><b>Ownership gate</b> – a soulbound spawner only spawns while its owner
 *       is online, in the same world, and within {@code spawn-distance} blocks.
 *       A soulbound-type spawner that isn't tracked spawns nothing. Matches the
 *       original SpawnerOwnerListener, minus its cross-world crash.</li>
 *   <li><b>Rate throttle</b> – above a player-count threshold, cancel a share of
 *       all spawner spawns. This is SBS's only spawn-limiting role; block counts
 *       are Insights' job and live-entity counts are MobFarmManager's.</li>
 * </ol>
 */
public final class SpawnerSpawnListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;

    public SpawnerSpawnListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onSpawnerSpawn(SpawnerSpawnEvent event) {
        if (event.getSpawner() == null) return;
        Location spawnerLoc = event.getSpawner().getBlock().getLocation();
        BlockKey key = BlockKey.of(spawnerLoc);

        OwnershipService ownership = plugin.ownership();
        boolean degraded = ownership.isDegraded();
        String spawnedType = event.getEntityType() != null ? event.getEntityType().name() : null;
        OwnedSpawner owned = ownership.get(key);

        if (!degraded) {
            boolean typeIsSoulbound = plugin.soulboundTypes().isSoulbound(spawnedType);

            if (owned == null) {
                // Untracked. A soulbound-type spawner with no owner produces nothing
                // (preserves the original behaviour); anything else is left alone.
                if (typeIsSoulbound) {
                    event.setCancelled(true);
                    return;
                }
            } else {
                if (owned.owner() == null) {
                    event.setCancelled(true);
                    return;
                }
                Player ownerPlayer = plugin.getServer().getPlayer(owned.owner());
                if (ownerPlayer == null) {
                    event.setCancelled(true);
                    return;
                }
                Location ol = ownerPlayer.getLocation();
                if (ol.getWorld() == null || !ol.getWorld().equals(spawnerLoc.getWorld())) {
                    event.setCancelled(true);
                    return;
                }
                double maxDist = plugin.config().spawnDistance();
                if (ol.distanceSquared(spawnerLoc) > maxDist * maxDist) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        // rate throttle – applies to every spawner
        if (plugin.config().throttleEnabled()) {
            int online = plugin.getServer().getOnlinePlayers().size();
            if (online > plugin.config().throttlePlayerThreshold()) {
                double pct = plugin.config().throttleReductionPct();
                if (pct >= 100 || ThreadLocalRandom.current().nextDouble() * 100.0 < pct) {
                    event.setCancelled(true);
                }
            }
        }
    }
}
