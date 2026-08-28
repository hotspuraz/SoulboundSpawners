package evo.soulboundspawners.listener;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Keeps spawners intact through explosions and the wither / ender dragon –
 * the effective behaviour of the live servers today. Both are behind config
 * flags ({@code protection.block-explosions}, {@code protection.block-wither})
 * that default on.
 */
public final class SpawnerProtectionListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;

    public SpawnerProtectionListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (plugin.config().blockExplosions()) {
            e.blockList().removeIf(b -> b.getType() == Material.SPAWNER);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (plugin.config().blockExplosions()) {
            e.blockList().removeIf(b -> b.getType() == Material.SPAWNER);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!plugin.config().blockWither()) return;
        if (e.getBlock().getType() != Material.SPAWNER) return;
        EntityType t = e.getEntityType();
        if (t == EntityType.WITHER || t == EntityType.ENDER_DRAGON) {
            e.setCancelled(true);
        }
    }
}
