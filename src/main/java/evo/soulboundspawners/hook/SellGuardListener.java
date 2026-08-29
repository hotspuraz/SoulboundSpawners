package evo.soulboundspawners.hook;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * Stops soulbound spawner items (anything carrying an owner) from being listed
 * or sold through third-party plugins.
 *
 * <p>Plugin-agnostic and config-driven: for every class named in
 * {@code sell-guard.events}, if it is a {@link Cancellable} event that exposes
 * an {@link ItemStack} (or a collection/array of them) and one is owned, the
 * event is cancelled and the actor told. Add event classes in config if a sell
 * route slips through – no recompile needed.
 */
public final class SellGuardListener implements Listener {

    private final SoulboundSpawnersPlugin plugin;

    public SellGuardListener(SoulboundSpawnersPlugin plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("unchecked")
    public void register(List<String> eventClassNames) {
        for (String name : eventClassNames) {
            try {
                Class<?> cls = Class.forName(name);
                if (!Event.class.isAssignableFrom(cls)) {
                    plugin.getLogger().warning("[sell-guard] " + name + " is not a Bukkit event – skipped.");
                    continue;
                }
                plugin.getServer().getPluginManager().registerEvent(
                        (Class<? extends Event>) cls, this, EventPriority.HIGH,
                        (listener, event) -> handle(event), plugin, true);
                plugin.getLogger().info("[sell-guard] Watching " + name);
            } catch (ClassNotFoundException e) {
                // that plugin isn't installed – fine
            } catch (Throwable t) {
                plugin.getLogger().warning("[sell-guard] Could not watch " + name + ": " + t.getMessage());
            }
        }
    }

    private void handle(Event event) {
        if (!(event instanceof Cancellable cancellable)) return;
        if (cancellable.isCancelled()) return;

        for (ItemStack item : extractItems(event)) {
            if (item != null && plugin.spawnerItems().readOwner(item) != null) {
                cancellable.setCancelled(true);
                CommandSender who = extractActor(event);
                if (who != null) plugin.notify(who, plugin.config().msg("cant-sell-soulbound"));
                return;
            }
        }
    }

    private Iterable<ItemStack> extractItems(Event event) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (Method m : event.getClass().getMethods()) {
            if (m.getParameterCount() != 0) continue;
            String n = m.getName().toLowerCase();
            if (!(n.contains("item") || n.contains("stack"))) continue;
            try {
                Object v = m.invoke(event);
                if (v instanceof ItemStack is) {
                    out.add(is);
                } else if (v instanceof ItemStack[] arr) {
                    for (ItemStack is : arr) if (is != null) out.add(is);
                } else if (v instanceof Collection<?> col) {
                    for (Object o : col) if (o instanceof ItemStack is) out.add(is);
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return out;
    }

    private CommandSender extractActor(Event event) {
        for (String getter : List.of("getPlayer", "getWhoClicked", "getSender", "getSeller", "getClient", "getOwner")) {
            try {
                Method m = event.getClass().getMethod(getter);
                Object v = m.invoke(event);
                if (v instanceof Player p) return p;
                if (v instanceof CommandSender s) return s;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
