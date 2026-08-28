package evo.soulboundspawners.spawner;

import evo.soulboundspawners.Text;
import evo.soulboundspawners.config.PluginConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the mob-type / owner data on spawner items.
 *
 * <p>Compatibility is the whole point of this class:
 * <ul>
 *   <li><b>New items</b> store {@code mob} / {@code owner} in the Bukkit
 *       {@link org.bukkit.persistence.PersistentDataContainer}.</li>
 *   <li><b>Old MineableSpawners items</b> carry root NBT tags {@code ms_mob} /
 *       {@code ms_owner} (written by NBT-API into the custom-data component).
 *       We read those out of the item's serialised NBT string.</li>
 *   <li><b>Legacy v1/v2 items</b> encode the type in the display name
 *       ({@code [Zombie] Spawner}) or a lore line ({@code Type: §7ZOMBIE}) with
 *       no NBT at all – parsed when {@code global.backwards-compatibility} is on.</li>
 * </ul>
 * The {@code ms_mob}/{@code ms_owner} tag names are deliberately kept even
 * though the plugin was renamed, so nothing in a chest breaks.
 */
public final class SpawnerItems {

    private static final Pattern MS_MOB   = Pattern.compile("\"?ms_mob\"?\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MS_OWNER = Pattern.compile("\"?ms_owner\"?\\s*:\\s*\"([^\"]+)\"");
    private static final LegacyComponentSerializer SECTION = LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final Plugin plugin;
    private final PluginConfig config;
    private final SoulboundTypes soulbound;
    private final NamespacedKey mobKey;
    private final NamespacedKey ownerKey;

    public SpawnerItems(Plugin plugin, PluginConfig config, SoulboundTypes soulbound) {
        this.plugin = plugin;
        this.config = config;
        this.soulbound = soulbound;
        this.mobKey = new NamespacedKey(plugin, "mob");
        this.ownerKey = new NamespacedKey(plugin, "owner");
    }

    public boolean isSpawner(ItemStack item) {
        return item != null && item.getType() == Material.SPAWNER;
    }

    /** @return the spawner's mob type, or null if this isn't one of our / a recognisable spawner. */
    public EntityType readType(ItemStack item) {
        if (!isSpawner(item)) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        // 1. modern PDC
        String pdc = meta.getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
        EntityType t = toEntityType(pdc);
        if (t != null) return t;

        // 2. legacy root NBT tag ms_mob
        t = toEntityType(rootTag(meta, MS_MOB));
        if (t != null) return t;

        if (!config.backwardsCompatibility()) return null;

        // 3. v2 – type in the display name: "[Zombie] Spawner"
        try {
            if (meta.hasDisplayName()) {
                String name = PLAIN.serialize(meta.displayName());
                String guess = name.split(" Spawner")[0].replace("[", "").replace("]", "").trim()
                        .replace(' ', '_').toUpperCase(Locale.ROOT);
                t = toEntityType(guess);
                if (t != null) return t;
            }
        } catch (RuntimeException ignored) {}

        // 4. v1 – type in a lore line: "Type: §7ZOMBIE"
        try {
            List<Component> lore = meta.lore();
            if (lore != null) {
                StringBuilder sb = new StringBuilder();
                for (Component line : lore) sb.append('\n').append(SECTION.serialize(line));
                String joined = sb.toString();
                int i = joined.indexOf(": §7");
                if (i >= 0) {
                    String after = joined.substring(i + 3);
                    String guess = after.split("[\\]\\n]")[0].trim().toUpperCase(Locale.ROOT);
                    t = toEntityType(guess);
                    if (t != null) return t;
                }
            }
        } catch (RuntimeException ignored) {}

        return null;
    }

    /** @return the encoded owner UUID, or null. */
    public UUID readOwner(ItemStack item) {
        if (!isSpawner(item)) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        String pdc = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        UUID u = toUuid(pdc);
        if (u != null) return u;

        return toUuid(rootTag(meta, MS_OWNER));
    }

    /** True when the item is a recognisable spawner that is NOT already in our PDC format. */
    public boolean needsUpgrade(ItemStack item) {
        if (!isSpawner(item)) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (meta.getPersistentDataContainer().has(mobKey, PersistentDataType.STRING)) return false;
        return readType(item) != null;
    }

    /** Rebuild an item in the canonical format, preserving type, owner and amount. */
    public ItemStack upgrade(ItemStack old) {
        EntityType type = readType(old);
        UUID owner = readOwner(old);
        ItemStack fresh = create(type, owner, Math.max(1, old.getAmount()));
        return fresh;
    }

    /**
     * Create a spawner item. Owner is only written for soulbound types (matches
     * the original API.getSpawnerFromEntityType behaviour).
     */
    public ItemStack create(EntityType type, UUID owner, int amount) {
        ItemStack item = new ItemStack(Material.SPAWNER, Math.max(1, amount));
        boolean isSoulbound = type != null && soulbound.isSoulbound(type);
        UUID effectiveOwner = isSoulbound ? owner : null;
        String mob = type == null ? "Empty" : Text.prettyMob(type.name());

        item.editMeta(meta -> {
            meta.displayName(Text.color(config.displayName().replace("%mob%", mob)));

            if (config.loreEnabled()) {
                List<Component> lore = new ArrayList<>();
                for (String line : config.displayLore()) {
                    if (line.toLowerCase(Locale.ROOT).contains("%owner%")) {
                        if (effectiveOwner == null) continue; // owner line only for owned soulbound items
                        lore.add(Text.color(line
                                .replace("%owner%", nameOf(effectiveOwner))
                                .replace("%mob%", mob)));
                    } else {
                        lore.add(Text.color(line.replace("%mob%", mob)));
                    }
                }
                meta.lore(lore);
            }

            var pdc = meta.getPersistentDataContainer();
            if (type != null) pdc.set(mobKey, PersistentDataType.STRING, type.name());
            else pdc.remove(mobKey);
            if (effectiveOwner != null) pdc.set(ownerKey, PersistentDataType.STRING, effectiveOwner.toString());
            else pdc.remove(ownerKey);

            for (ItemFlag f : ItemFlag.values()) meta.addItemFlags(f);
        });
        return item;
    }

    /** Set only the owner on an existing item (used by /sbs item owner). */
    public ItemStack withOwner(ItemStack base, UUID owner) {
        EntityType type = readType(base);
        return create(type, owner, base.getAmount());
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) return "Unknown";
        OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
        String n = op.getName();
        return n != null ? n : uuid.toString().substring(0, 8);
    }

    // --- internals ---

    private String rootTag(ItemMeta meta, Pattern p) {
        try {
            String snbt = meta.getAsString();
            if (snbt == null) return null;
            Matcher m = p.matcher(snbt);
            return m.find() ? m.group(1) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static EntityType toEntityType(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static UUID toUuid(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("null")) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
