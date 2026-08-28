package evo.soulboundspawners.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Typed view over config.yml. Thin on purpose: unknown keys fall back to
 * sensible defaults rather than throwing, which is what bit the old
 * ConfigurationHandler.
 */
public final class PluginConfig {

    private final FileConfiguration c;

    public PluginConfig(FileConfiguration c) {
        this.c = c;
    }

    public FileConfiguration raw() {
        return c;
    }

    // --- global ---
    public boolean backwardsCompatibility() { return c.getBoolean("global.backwards-compatibility", true); }
    public boolean fixItemsOnJoin()          { return c.getBoolean("global.fix-items-on-join", true); }
    public boolean debug()                   { return c.getBoolean("global.debug", false); }
    public String displayName()              { return c.getString("global.display.name", "&8[&e%mob% &7Spawner&8]"); }
    public boolean loreEnabled()             { return c.getBoolean("global.display.lore-enabled", true); }
    public List<String> displayLore()        { return c.getStringList("global.display.lore"); }

    // --- soulbound ---
    public List<String> soulboundTypes() {
        List<String> l = c.getStringList("soulbound.types");
        return l.isEmpty() ? List.of("EVOKER","GHAST","IRON_GOLEM","PHANTOM","RAVAGER","SLIME","SHULKER","VINDICATOR","WITHER","WITCH") : l;
    }
    public double spawnDistance() { return c.getDouble("soulbound.spawn-distance", 16); }

    // --- performance / spawn-rate throttle ---
    public boolean throttleEnabled()      { return c.getBoolean("performance.enabled", true); }
    public int throttlePlayerThreshold()  { return c.getInt("performance.player-threshold", 45); }
    public double throttleReductionPct()  {
        Object v = c.get("performance.spawn-reduction-percent", 80.0);
        return parsePercent(v);
    }

    // --- storage ---
    public String storageFileName() { return c.getString("storage.file", "spawners.db"); }

    // --- protection ---
    public boolean blockExplosions()     { return c.getBoolean("protection.block-explosions", true); }
    public boolean blockWither()         { return c.getBoolean("protection.block-wither", true); }
    public boolean preventAnvilRename()  { return c.getBoolean("protection.prevent-anvil-rename", true); }

    // --- mining ---
    public boolean miningRequirePermission()           { return c.getBoolean("mining.require-permission", false); }
    public boolean miningRequireIndividualPermission() { return c.getBoolean("mining.require-individual-permission", false); }
    public List<String> miningTools()                  { return c.getStringList("mining.tools"); }
    public boolean miningAnyPickaxe()                  { return c.getBoolean("mining.any-pickaxe", true); }
    public boolean miningRequireSilktouch()            { return c.getBoolean("mining.require-silktouch", true); }
    public boolean miningRequireSilktouchLevel()       { return c.getBoolean("mining.require-silktouch-level", false); }
    public int miningRequiredLevel()                   { return c.getInt("mining.required-level", 2); }
    public double miningChance()                       { return c.getDouble("mining.chance", 100); }
    public boolean miningUsePermChances()              { return c.getBoolean("mining.use-perm-based-chances", false); }
    public boolean miningDropToInventory()             { return c.getBoolean("mining.drop-to-inventory", true); }
    public boolean miningDropExp()                     { return c.getBoolean("mining.drop-exp", false); }
    public boolean miningStillBreak()                  { return c.getBoolean("mining.still-break", false); }
    public boolean miningCharge()                      { return c.getBoolean("mining.charge", false); }
    public List<String> miningBlacklistedWorlds()      { return c.getStringList("mining.blacklisted-worlds"); }
    public List<String> miningPrices()                 { return c.getStringList("mining.prices"); }

    /** Ordered permission -> chance% (LinkedHashMap: config order is respected, unlike the old HashMap). */
    public Map<String, Double> miningPermChances() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String line : c.getStringList("mining.perm-based-chances")) {
            int i = line.lastIndexOf(':');
            if (i <= 0) continue;
            try {
                out.put(line.substring(0, i).trim(), Double.parseDouble(line.substring(i + 1).trim()));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    // --- placing ---
    public boolean placingLog()                   { return c.getBoolean("placing.log", true); }
    public boolean placingCharge()                { return c.getBoolean("placing.charge", false); }
    public List<String> placingBlacklistedWorlds(){ return c.getStringList("placing.blacklisted-worlds"); }
    public List<String> placingPrices()           { return c.getStringList("placing.prices"); }

    // --- give ---
    public boolean giveRequirePermission() { return c.getBoolean("give.require-permission", true); }
    public boolean giveDropIfFull()        { return c.getBoolean("give.drop-if-full", true); }

    // --- types ---
    public boolean typesRequirePermission() { return c.getBoolean("types.require-permission", true); }
    public String typesTitle()              { return c.getString("types.message-title", "&eAvailable Types: "); }
    public String typesEntry()              { return c.getString("types.message-entry", "&f%mob%&7, "); }

    // --- messages ---
    public String msg(String key) {
        return c.getString("messages." + key, "");
    }
    public String miningMsg(String key) {
        return c.getString("mining.messages." + key, "");
    }
    public String miningRequirement(String key) {
        return c.getString("mining.requirements." + key, "");
    }
    public String placingMsg(String key) {
        return c.getString("placing.messages." + key, "");
    }
    public String giveMsg(String key) {
        return c.getString("give.messages." + key, "");
    }

    // --- helpers ---
    public static double parsePercent(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return 100;
        String s = v.toString().trim().toLowerCase(Locale.ROOT).replace("%", "");
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 100; }
    }
}
