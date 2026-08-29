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
                if (owned.owner() == null || !someoneAuthorisedNearby(owned.owner(), spawnerLoc)) {
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

    /** Owner, or (if enabled) an online married partner, in the same world and within spawn-distance. */
    private boolean someoneAuthorisedNearby(java.util.UUID owner, Location spawnerLoc) {
        double maxDist = plugin.config().spawnDistance();
        Player ownerPlayer = plugin.getServer().getPlayer(owner);
        if (inRange(ownerPlayer, spawnerLoc, maxDist)) return true;

        if (plugin.config().soulmateEnabled() && plugin.config().soulmateSpawnNearby()
                && plugin.marriage().isAvailable() && plugin.prefs().partnerSpawn(owner)) {
            for (Player partner : plugin.marriage().onlinePartners(owner)) {
                if (inRange(partner, spawnerLoc, maxDist)) return true;
            }
        }
        return false;
    }

    private static boolean inRange(Player p, Location spawnerLoc, double maxDist) {
        if (p == null) return false;
        Location l = p.getLocation();
        if (l.getWorld() == null || !l.getWorld().equals(spawnerLoc.getWorld())) return false;
        return l.distanceSquared(spawnerLoc) <= maxDist * maxDist;
    }
}
