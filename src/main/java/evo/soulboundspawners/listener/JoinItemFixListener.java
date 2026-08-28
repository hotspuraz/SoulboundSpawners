package evo.soulboundspawners.listener;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * On join, rewrite any legacy-format spawner items in the player's inventory
 * into the canonical form so they keep working. Only touches items whose type
 * we can resolve; genuinely unidentifiable spawners are left untouched (and
 * logged at FINE) rather than shown a broken message – the original plugin's
 * message here was truncated mid-sentence.
 */
public final class JoinItemFixListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;

    public JoinItemFixListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.config().fixItemsOnJoin()) return;
        Player player = e.getPlayer();

        ItemStack[] contents = player.getInventory().getContents();
        int fixed = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack stack = contents[i];
            if (stack == null || !plugin.spawnerItems().isSpawner(stack)) continue;
            if (plugin.spawnerItems().needsUpgrade(stack)) {
                ItemStack upgraded = plugin.spawnerItems().upgrade(stack);
                player.getInventory().setItem(i, upgraded);
                fixed++;
            } else if (plugin.spawnerItems().readType(stack) == null) {
                plugin.getLogger().fine("Unidentifiable spawner item in " + player.getName()
                        + "'s inventory (slot " + i + ") – left untouched.");
            }
        }
        if (fixed > 0) {
            player.updateInventory();
            if (plugin.config().debug()) {
                plugin.getLogger().info("Upgraded " + fixed + " spawner item(s) for " + player.getName() + ".");
            }
        }
    }
}
