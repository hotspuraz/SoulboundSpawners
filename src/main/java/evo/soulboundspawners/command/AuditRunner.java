package evo.soulboundspawners.command;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import evo.soulboundspawners.ownership.BlockKey;
import evo.soulboundspawners.ownership.OwnedSpawner;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only health report over the tracked-spawner data. Nothing is changed.
 * Run before and after a server's migration to see the blast radius.
 */
final class AuditRunner {

    private AuditRunner() {}

    static void run(SoulboundSpawnersPlugin plugin, CommandSender out) {
        List<OwnedSpawner> all = plugin.ownership().all();
        long dbRows = plugin.store().count();

        int nullOwner = 0, ownerNeverJoined = 0, invalidType = 0, unknownWorld = 0;
        int blockPresent = 0, blockGone = 0, unchecked = 0;
        Map<String, Integer> perWorld = new TreeMap<>();
        List<String> sampleGone = new ArrayList<>();
        List<String> sampleUnknownWorld = new ArrayList<>();
        List<String> sampleNullOwner = new ArrayList<>();

        for (OwnedSpawner s : all) {
            BlockKey key = s.key();
            perWorld.merge(key.world(), 1, Integer::sum);

            if (s.owner() == null) {
                nullOwner++;
                if (sampleNullOwner.size() < 15) sampleNullOwner.add(key.toLegacyString() + " type=" + s.entityType());
            } else if (!Bukkit.getOfflinePlayer(s.owner()).hasPlayedBefore()) {
                ownerNeverJoined++;
            }

            if (s.entityType() != null) {
                try {
                    EntityType.valueOf(s.entityType().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    invalidType++;
                }
            }

            World world = Bukkit.getWorld(key.world());
            if (world == null) {
                unknownWorld++;
                if (sampleUnknownWorld.size() < 15) sampleUnknownWorld.add(key.toLegacyString());
                continue;
            }
            if (!world.isChunkLoaded(key.x() >> 4, key.z() >> 4)) {
                unchecked++;
                continue;
            }
            if (world.getBlockAt(key.x(), key.y(), key.z()).getType() == Material.SPAWNER) {
                blockPresent++;
            } else {
                blockGone++;
                if (sampleGone.size() < 15) sampleGone.add(key.toLegacyString() + " type=" + s.entityType());
            }
        }

        // --- to the runner ---
        plugin.send(out, "&e&lSoulboundSpawners audit");
        plugin.send(out, "&7Tracked in cache: &f" + all.size() + " &7 | rows in DB: &f" + dbRows
                + (all.size() == dbRows ? "" : " &c(MISMATCH)"));
        plugin.send(out, "&7Null / no owner: &f" + nullOwner + colourIf(nullOwner));
        plugin.send(out, "&7Owner never joined this server: &f" + ownerNeverJoined);
        plugin.send(out, "&7Invalid entity type: &f" + invalidType + colourIf(invalidType));
        plugin.send(out, "&7Rows in an unknown/unloaded world: &f" + unknownWorld + colourIf(unknownWorld));
        plugin.send(out, "&7Block check (loaded chunks only): &aspawner present " + blockPresent
                + " &c/ block gone " + blockGone + " &8/ unchecked (chunk unloaded) " + unchecked);
        plugin.send(out, "&7Per world: &f" + perWorld);
        if (blockGone > 0 || unknownWorld > 0 || nullOwner > 0) {
            plugin.send(out, "&7Full detail (locations) in console.");
        }

        // --- to console, greppable ---
        var log = plugin.getLogger();
        log.info("[audit] cache=" + all.size() + " db=" + dbRows + " nullOwner=" + nullOwner
                + " ownerNeverJoined=" + ownerNeverJoined + " invalidType=" + invalidType
                + " unknownWorld=" + unknownWorld + " blockPresent=" + blockPresent
                + " blockGone=" + blockGone + " unchecked=" + unchecked + " perWorld=" + perWorld);
        sampleNullOwner.forEach(x -> log.info("[audit] null-owner: " + x));
        sampleUnknownWorld.forEach(x -> log.info("[audit] unknown-world: " + x));
        sampleGone.forEach(x -> log.info("[audit] block-gone: " + x));
    }

    private static String colourIf(int n) {
        return n > 0 ? " &c<-- review" : "";
    }
}
