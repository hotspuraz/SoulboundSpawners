package evo.soulboundspawners.ownership;

import org.bukkit.Location;
import org.bukkit.block.Block;

/**
 * Immutable world-name + block-coordinate key. This is exactly how the original
 * plugin (via AtherialLib's AtherialXYZLocation) identified a tracked spawner:
 * world name plus {@code getBlockX/Y/Z}. Keep this derivation identical or old
 * rows stop matching.
 */
public record BlockKey(String world, int x, int y, int z) {

    public static BlockKey of(Location loc) {
        return new BlockKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    /** The storage string format used by the legacy {@code mspawners.location} column: {@code world,x,y,z}. */
    public static BlockKey parseLegacy(String s) {
        if (s == null) return null;
        String[] p = s.replace(" ", "").split(",");
        if (p.length != 4) return null;
        try {
            return new BlockKey(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String toLegacyString() {
        return world + "," + x + "," + y + "," + z;
    }
}
