package evo.soulboundspawners.command;

import evo.soulboundspawners.SoulboundSpawnersPlugin;
import evo.soulboundspawners.ownership.BlockKey;
import evo.soulboundspawners.ownership.OwnedSpawner;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only health report over the tracked-spawner data. Nothing is changed.
 * Run before and after a server's migration to see the blast radius.
 *
 * <p>{@code /sbs audit} checks blocks only in already-loaded chunks (instant).
 * {@code /sbs audit full} loads every chunk that has a tracked spawner, a few
 * per tick, checks them all, and unloads the ones it loaded.
 */
final class AuditRunner {

    private AuditRunner() {}

    // ---- metadata pass (shared) ----

    private record Meta(int nullOwner, int ownerNeverJoined, int invalidType, int unknownWorld,
                        Map<String, Integer> perWorld, List<String> sampleNullOwner) {}

    private static Meta scanMeta(List<OwnedSpawner> all) {
        int nullOwner = 0, ownerNeverJoined = 0, invalidType = 0, unknownWorld = 0;
        Map<String, Integer> perWorld = new TreeMap<>();
        List<String> sampleNullOwner = new ArrayList<>();
        for (OwnedSpawner s : all) {
            perWorld.merge(s.key().world(), 1, Integer::sum);
            if (s.owner() == null) {
                nullOwner++;
                if (sampleNullOwner.size() < 20) sampleNullOwner.add(s.key().toLegacyString() + " type=" + s.entityType());
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
            if (Bukkit.getWorld(s.key().world()) == null) unknownWorld++;
        }
        return new Meta(nullOwner, ownerNeverJoined, invalidType, unknownWorld, perWorld, sampleNullOwner);
    }

    private static void printHeader(SoulboundSpawnersPlugin plugin, CommandSender out, List<OwnedSpawner> all, Meta m) {
        long dbRows = plugin.store().count();
        plugin.send(out, "&e&lSoulboundSpawners audit");
        plugin.send(out, "&7Tracked in cache: &f" + all.size() + " &7| rows in DB: &f" + dbRows
                + (all.size() == dbRows ? "" : " &c(MISMATCH)"));
        plugin.send(out, "&7Null / no owner: &f" + m.nullOwner() + flag(m.nullOwner()));
        plugin.send(out, "&7Owner never joined this server: &f" + m.ownerNeverJoined()
                + " &8(benign - spawner activates when they log in)");
        plugin.send(out, "&7Invalid entity type: &f" + m.invalidType() + flag(m.invalidType()));
        plugin.send(out, "&7Rows in an unknown world: &f" + m.unknownWorld() + flag(m.unknownWorld()));
        plugin.send(out, "&7Per world: &f" + m.perWorld());
        plugin.getLogger().info("[audit] cache=" + all.size() + " db=" + dbRows
                + " nullOwner=" + m.nullOwner() + " ownerNeverJoined=" + m.ownerNeverJoined()
                + " invalidType=" + m.invalidType() + " unknownWorld=" + m.unknownWorld()
                + " perWorld=" + m.perWorld());
        m.sampleNullOwner().forEach(x -> plugin.getLogger().info("[audit] null-owner: " + x));
    }

    // ---- quick (loaded chunks only) ----

    static void run(SoulboundSpawnersPlugin plugin, CommandSender out) {
        List<OwnedSpawner> all = plugin.ownership().all();
        Meta m = scanMeta(all);
        printHeader(plugin, out, all, m);

        int present = 0, gone = 0, unchecked = 0;
        List<String> sampleGone = new ArrayList<>();
        for (OwnedSpawner s : all) {
            BlockKey k = s.key();
            World w = Bukkit.getWorld(k.world());
            if (w == null) { unchecked++; continue; }
            if (!w.isChunkLoaded(k.x() >> 4, k.z() >> 4)) { unchecked++; continue; }
            if (w.getBlockAt(k.x(), k.y(), k.z()).getType() == Material.SPAWNER) present++;
            else { gone++; if (sampleGone.size() < 20) sampleGone.add(k.toLegacyString() + " type=" + s.entityType()); }
        }
        plugin.send(out, "&7Block check &8(loaded chunks only)&7: &apresent " + present
                + " &c/ gone " + gone + " &8/ unchecked " + unchecked);
        if (unchecked > 0) plugin.send(out, "&8Run &7/sbs audit full &8to load every chunk and check all of them.");
        plugin.getLogger().info("[audit] blockPresent=" + present + " blockGone=" + gone + " unchecked=" + unchecked);
        sampleGone.forEach(x -> plugin.getLogger().info("[audit] block-gone: " + x));
    }

    // ---- full (loads chunks, a few per tick) ----

    static void runFull(SoulboundSpawnersPlugin plugin, CommandSender out) {
        sweep(plugin, out, false);
    }

    /** Same sweep, but delete the confirmed-orphan rows (chunk loaded, block is not a spawner). */
    static void runPrune(SoulboundSpawnersPlugin plugin, CommandSender out) {
        sweep(plugin, out, true);
    }

    private static void sweep(SoulboundSpawnersPlugin plugin, CommandSender out, boolean prune) {
        List<OwnedSpawner> all = plugin.ownership().all();
        Meta m = scanMeta(all);
        printHeader(plugin, out, all, m);

        Map<String, List<OwnedSpawner>> byChunk = new LinkedHashMap<>();
        for (OwnedSpawner s : all) {
            BlockKey k = s.key();
            if (Bukkit.getWorld(k.world()) == null) continue;
            String ck = k.world() + "|" + (k.x() >> 4) + "|" + (k.z() >> 4);
            byChunk.computeIfAbsent(ck, x -> new ArrayList<>()).add(s);
        }
        Deque<List<OwnedSpawner>> queue = new ArrayDeque<>(byChunk.values());
        int totalChunks = queue.size();
        plugin.send(out, "&7" + (prune ? "Pruning" : "Checking") + " &f" + all.size()
                + "&7 spawners across &f" + totalChunks + "&7 chunks - loads chunks briefly, expect minor lag.");

        int[] present = {0}, gone = {0}, missingChunk = {0}, doneChunks = {0};
        List<String> sampleGone = new ArrayList<>();
        List<OwnedSpawner> confirmedOrphans = new ArrayList<>();

        new BukkitRunnable() {
            @Override
            public void run() {
                for (int i = 0; i < 6 && !queue.isEmpty(); i++) {
                    List<OwnedSpawner> group = queue.poll();
                    BlockKey any = group.get(0).key();
                    World w = Bukkit.getWorld(any.world());
                    int cx = any.x() >> 4, cz = any.z() >> 4;
                    boolean wasLoaded = w.isChunkLoaded(cx, cz);
                    boolean exists = w.loadChunk(cx, cz, false);
                    if (!exists) {
                        missingChunk[0] += group.size(); // NOT an orphan - chunk unreadable, leave it
                    } else {
                        for (OwnedSpawner s : group) {
                            BlockKey k = s.key();
                            if (w.getBlockAt(k.x(), k.y(), k.z()).getType() == Material.SPAWNER) {
                                present[0]++;
                            } else {
                                gone[0]++;
                                confirmedOrphans.add(s);
                                if (sampleGone.size() < 50) sampleGone.add(k.toLegacyString() + " type=" + s.entityType());
                            }
                        }
                        if (!wasLoaded) w.unloadChunkRequest(cx, cz);
                    }
                    doneChunks[0]++;
                }

                if ((doneChunks[0] % Math.max(1, totalChunks / 5)) < 6 && !queue.isEmpty()) {
                    plugin.send(out, "&8" + (prune ? "prune" : "audit") + ": " + (doneChunks[0] * 100 / totalChunks) + "%");
                }

                if (!queue.isEmpty()) return;
                cancel();

                plugin.send(out, "&7Block check &8(full)&7: &apresent " + present[0]
                        + " &c/ gone " + gone[0] + " &8/ chunk never generated " + missingChunk[0]);
                plugin.getLogger().info("[audit] FULL blockPresent=" + present[0] + " blockGone=" + gone[0]
                        + " chunkNeverGenerated=" + missingChunk[0]);
                sampleGone.forEach(x -> plugin.getLogger().info("[audit] block-gone: " + x));

                if (!prune) {
                    if (gone[0] > 0) plugin.send(out, "&eRun &7/sbs audit prune confirm&e to delete the "
                            + gone[0] + " rows with no block (a backup JSON is written first).");
                    else plugin.send(out, "&aEvery tracked spawner still has a spawner block.");
                    return;
                }

                // prune
                if (confirmedOrphans.isEmpty()) {
                    plugin.send(out, "&aNothing to prune.");
                    return;
                }
                File backup = writeOrphanBackup(plugin, confirmedOrphans);
                for (OwnedSpawner s : confirmedOrphans) plugin.ownership().unregister(s.key());
                plugin.send(out, "&aPruned &f" + confirmedOrphans.size()
                        + "&a orphan rows. Backup: &f" + (backup == null ? "(failed)" : backup.getName()));
                plugin.getLogger().info("[audit] PRUNED " + confirmedOrphans.size() + " orphan rows, backup="
                        + (backup == null ? "none" : backup.getName()));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static File writeOrphanBackup(SoulboundSpawnersPlugin plugin, List<OwnedSpawner> rows) {
        try {
            var gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            List<Map<String, Object>> list = new ArrayList<>();
            for (OwnedSpawner s : rows) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("world", s.key().world());
                mm.put("x", s.key().x());
                mm.put("y", s.key().y());
                mm.put("z", s.key().z());
                mm.put("type", s.entityType());
                mm.put("owner", s.owner() == null ? null : s.owner().toString());
                list.add(mm);
            }
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .format(java.time.LocalDateTime.now());
            File f = new File(plugin.getDataFolder(), "prune-backup-" + stamp + ".json");
            java.nio.file.Files.writeString(f.toPath(), gson.toJson(Map.of("pruned", list)));
            return f;
        } catch (Exception e) {
            plugin.getLogger().warning("[audit] could not write prune backup: " + e.getMessage());
            return null;
        }
    }

    private static String flag(int n) {
        return n > 0 ? " &c<-- review" : "";
    }
}
