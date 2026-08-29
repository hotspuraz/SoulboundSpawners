package evo.soulboundspawners.listener;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Stops a spawner being re-typed by right-clicking it with a spawn egg. Vanilla
 * allows this; the deployed MineableSpawners blocked it for everyone (its
 * {@code eggs.*} permission config was dead code — the listener always
 * cancelled). We keep the block, behind {@code protection.block-egg-changes}
 * (default on), with a {@code soulboundspawners.bypass} exemption.
 */
public final class SpawnerEggListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;

    public SpawnerEggListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (!plugin.config().blockEggChanges()) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = e.getClickedBlock();
        if (block == null || block.getType() != Material.SPAWNER) return;

        ItemStack item = e.getItem();
        if (item == null || !item.getType().name().endsWith("_SPAWN_EGG")) return;

        Player player = e.getPlayer();
        if (plugin.perms().hasBypass(player)) return;

        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) {
            plugin.notify(player, plugin.config().msg("egg-change-blocked"));
        }
    }
}
