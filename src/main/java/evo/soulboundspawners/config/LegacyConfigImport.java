package evo.soulboundspawners.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * One-time import of the old MineableSpawners config. Runs when our own
 * config.yml has just been created from defaults and a
 * {@code plugins/MineableSpawners/} folder is present. Writes an
 * {@code .imported} marker so it never runs twice.
 */
public final class LegacyConfigImport {

    private LegacyConfigImport() {}

    public static void run(File pluginsFolder, File ourDataFolder, FileConfiguration target, Logger log) {
        File marker = new File(ourDataFolder, ".imported");
        if (marker.exists()) return;

        File oldMain = new File(pluginsFolder, "MineableSpawners/config.yml");
        File oldNewC = new File(pluginsFolder, "MineableSpawners/newC.yml");
        if (!oldMain.isFile() && !oldNewC.isFile()) {
            touch(marker);
            return;
        }

        log.info("[SoulboundSpawners] Importing configuration from plugins/MineableSpawners/ ...");
        YamlConfiguration main = oldMain.isFile() ? YamlConfiguration.loadConfiguration(oldMain) : new YamlConfiguration();
        YamlConfiguration newC = oldNewC.isFile() ? YamlConfiguration.loadConfiguration(oldNewC) : new YamlConfiguration();

        // global / display
        copy(main, "global.backwards-compatibility", target, "global.backwards-compatibility");
        copy(main, "global.display.name", target, "global.display.name");
        copyList(main, "global.display.lore", target, "global.display.lore");
        copy(main, "global.display.lore-enabled", target, "global.display.lore-enabled");

        // newC.yml
        List<String> soulbound = newC.getStringList("soulbound");
        if (!soulbound.isEmpty()) target.set("soulbound.types", soulbound);
        if (newC.isSet("spawnDistance")) target.set("soulbound.spawn-distance", newC.getInt("spawnDistance"));
        if (newC.isSet("performance.onlineAmt")) target.set("performance.player-threshold", newC.getInt("performance.onlineAmt"));
        if (newC.isSet("performance.reduceRate"))
            target.set("performance.spawn-reduction-percent", PluginConfig.parsePercent(newC.get("performance.reduceRate")));
        copy(newC, "msg.NOT_OWNER_PLACE_ITEM", target, "messages.not-owner-place");
        copy(newC, "msg.NOT_OWNER_CANT_BREAK", target, "messages.not-owner-break");
        copy(newC, "msg.CANT_LIST_SOUL_BOUND", target, "messages.cant-sell-soulbound");

        // mining – keys line up 1:1
        for (String k : List.of("require-permission", "require-individual-permission", "require-silktouch",
                "require-silktouch-level", "required-level", "chance", "use-perm-based-chances",
                "drop-to-inventory", "drop-exp", "still-break", "charge")) {
            copy(main, "mining." + k, target, "mining." + k);
        }
        copyList(main, "mining.tools", target, "mining.tools");
        copyList(main, "mining.perm-based-chances", target, "mining.perm-based-chances");
        copyList(main, "mining.prices", target, "mining.prices");
        copyWorlds(main, "mining.blacklisted-worlds", target, "mining.blacklisted-worlds");
        copySection(main, "mining.messages", target, "mining.messages");
        copySection(main, "mining.requirements", target, "mining.requirements");

        // placing
        copy(main, "placing.log", target, "placing.log");
        copy(main, "placing.charge", target, "placing.charge");
        copyList(main, "placing.prices", target, "placing.prices");
        copyWorlds(main, "placing.blacklisted-worlds", target, "placing.blacklisted-worlds");
        copySection(main, "placing.messages", target, "placing.messages");

        // give
        copy(main, "give.require-permission", target, "give.require-permission");
        copy(main, "give.drop-if-full", target, "give.drop-if-full");
        copySection(main, "give.messages", target, "give.messages");

        // set -> type
        copy(main, "set.require-permission", target, "type.require-permission");
        copy(main, "set.require-individual-permission", target, "type.require-individual-permission");

        // types
        copy(main, "types.require-permission", target, "types.require-permission");
        copy(main, "types.messages.title", target, "types.message-title");
        copy(main, "types.messages.entries", target, "types.message-entry");

        // anvil -> protection
        if (main.isSet("anvil.prevent-anvil")) target.set("protection.prevent-anvil-rename", main.getBoolean("anvil.prevent-anvil"));
        copy(main, "anvil.messages.prevented", target, "messages.anvil-prevented");

        // NOTE: eggs.* is intentionally dropped. explode/wither drop-settings are
        // intentionally NOT imported – the live servers are already spawner-proof
        // for both, and protection.block-* defaults preserve that.

        touch(marker);
        log.info("[SoulboundSpawners] Import complete. Review plugins/SoulboundSpawners/config.yml.");
    }

    private static void copy(FileConfiguration from, String fromPath, FileConfiguration to, String toPath) {
        if (from.isSet(fromPath)) to.set(toPath, from.get(fromPath));
    }

    private static void copyList(FileConfiguration from, String fromPath, FileConfiguration to, String toPath) {
        if (from.isSet(fromPath)) {
            List<String> l = from.getStringList(fromPath);
            if (!l.isEmpty()) to.set(toPath, l);
        }
    }

    private static void copyWorlds(FileConfiguration from, String fromPath, FileConfiguration to, String toPath) {
        if (!from.isSet(fromPath)) return;
        List<String> cleaned = new ArrayList<>();
        for (String w : from.getStringList(fromPath)) {
            if (w != null && !w.equalsIgnoreCase("worldname")) cleaned.add(w);
        }
        to.set(toPath, cleaned);
    }

    private static void copySection(FileConfiguration from, String fromPath, FileConfiguration to, String toPath) {
        if (from.getConfigurationSection(fromPath) == null) return;
        for (String key : from.getConfigurationSection(fromPath).getKeys(true)) {
            Object v = from.get(fromPath + "." + key);
            if (v != null && !(v instanceof org.bukkit.configuration.ConfigurationSection)) {
                to.set(toPath + "." + key, v);
            }
        }
    }

    private static void touch(File f) {
        try {
            f.getParentFile().mkdirs();
            f.createNewFile();
        } catch (IOException ignored) {}
    }
}
