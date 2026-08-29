package evo.soulboundspawners.listener;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Removes the vanilla "right-click a spawner with a spawn egg to change its
 * type" behaviour, for everyone. No config, no permission, no bypass - the
 * same as the original MineableSpawners. Spawner types are set with
 * {@code /sbs type} / {@code /sbs item type}.
 */
public final class SpawnerEggListener implements Listener {

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;

        ItemStack item = e.getItem();
        if (item != null && item.getType().name().endsWith("_SPAWN_EGG")) {
            e.setCancelled(true);
        }
    }
}
