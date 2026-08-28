package evo.soulboundspawners;

import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permissible;

import java.util.Map;

/**
 * Permission checks. During the LuckPerms migration the plugin also accepts the
 * old {@code mineablespawners.*} nodes (config {@code legacy-permissions}).
 */
public final class Permissions {

    public static final String PREFIX = "soulboundspawners.";

    private static final Map<String, String> LEGACY = Map.ofEntries(
            Map.entry("bypass", "mineablespawners.bypass"),
            Map.entry("nosilk", "mineablespawners.nosilk"),
            Map.entry("mine", "mineablespawners.mine"),
            Map.entry("give", "mineablespawners.give"),
            Map.entry("type", "mineablespawners.set"),
            Map.entry("types", "mineablespawners.types"),
            Map.entry("reload", "mineablespawners.reload")
    );

    private boolean legacyEnabled = true;

    public void setLegacyEnabled(boolean enabled) {
        this.legacyEnabled = enabled;
    }

    /** {@code node} is the bit after {@code soulboundspawners.} */
    public boolean has(Permissible who, String node) {
        if (who.hasPermission(PREFIX + node)) return true;
        if (legacyEnabled) {
            String legacy = LEGACY.get(node);
            if (legacy != null && who.hasPermission(legacy)) return true;
        }
        return false;
    }

    /** Per-type nodes: {@code soulboundspawners.mine.<type>} / legacy {@code mineablespawners.mine.<type>}. */
    public boolean hasTyped(Permissible who, String base, String type) {
        String t = type.toLowerCase();
        if (who.hasPermission(PREFIX + base + "." + t)) return true;
        if (legacyEnabled && who.hasPermission("mineablespawners." + base + "." + t)) return true;
        return false;
    }

    public boolean hasBypass(CommandSender who) {
        return has(who, "bypass");
    }
}
