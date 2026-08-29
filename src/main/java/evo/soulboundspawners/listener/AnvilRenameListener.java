package evo.soulboundspawners.listener;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Stops spawners being renamed in anvils. With
 * {@code global.backwards-compatibility} on, a renamed spawner could no longer
 * be identified by the legacy name parser, so this stays enabled.
 */
public final class AnvilRenameListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;

    public AnvilRenameListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepare(PrepareAnvilEvent e) {
        if (!plugin.config().preventAnvilRename()) return;
        if (containsSpawner(e.getInventory().getContents())) {
            e.setResult(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!plugin.config().preventAnvilRename()) return;
        if (e.getInventory().getType() != InventoryType.ANVIL) return;
        ItemStack current = e.getCurrentItem();
        ItemStack cursor = e.getCursor();
        boolean touchingSpawner = (current != null && current.getType() == Material.SPAWNER)
                || (cursor != null && cursor.getType() == Material.SPAWNER);
        if (touchingSpawner && e.getRawSlot() == 2) {
            e.setCancelled(true);
            HumanEntity who = e.getWhoClicked();
            plugin.notify(who, plugin.config().msg("anvil-prevented"));
        }
    }

    private boolean containsSpawner(ItemStack[] items) {
        for (ItemStack i : items) {
            if (i != null && i.getType() == Material.SPAWNER) return true;
        }
        return false;
    }
}
