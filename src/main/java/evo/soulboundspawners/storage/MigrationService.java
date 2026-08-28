package evo.soulboundspawners.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import evo.soulboundspawners.ownership.BlockKey;
import evo.soulboundspawners.ownership.OwnedSpawner;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot MySQL ({@code mspawners}) → SQLite migration. Reads the old table,
 * parses each row, de-duplicates on location, writes a JSON backup and imports
 * into the live SQLite store. The MySQL table is only ever read.
 */
public final class MigrationService {

    public record Report(
            int rowsRead, int imported, int deduped, int badLocation, int badOwner,
            List<String> notes, File backupFile) {
    }

    private final SpawnerStore store;
    private final File dataFolder;

    public MigrationService(SpawnerStore store, File dataFolder) {
        this.store = store;
        this.dataFolder = dataFolder;
    }

    /** Blocking. Call off the main thread. */
    public Report run(LegacyDatabaseConfig cfg) throws Exception {
        List<OwnedSpawner> parsed = new ArrayList<>();
        Map<BlockKey, OwnedSpawner> byKey = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        int rowsRead = 0, badLoc = 0, badOwner = 0, deduped = 0;
        long now = System.currentTimeMillis();

        try (Connection c = connect(cfg);
             PreparedStatement ps = c.prepareStatement("SELECT uuid, location, type, owner FROM mspawners");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                rowsRead++;
                String location = rs.getString("location");
                String type = rs.getString("type");
                String ownerRaw = rs.getString("owner");

                BlockKey key = BlockKey.parseLegacy(location);
                if (key == null) {
                    badLoc++;
                    if (notes.size() < 40) notes.add("bad location skipped: uuid=" + rs.getString("uuid") + " location=" + location);
                    continue;
                }
                UUID owner = null;
                if (ownerRaw != null && !ownerRaw.isEmpty() && !ownerRaw.equalsIgnoreCase("null")) {
                    try {
                        owner = UUID.fromString(ownerRaw);
                    } catch (IllegalArgumentException e) {
                        badOwner++;
                        if (notes.size() < 40) notes.add("unparseable owner kept as null: location=" + location + " owner=" + ownerRaw);
                    }
                }
                OwnedSpawner s = new OwnedSpawner(key, type, owner, now);
                parsed.add(s);
                OwnedSpawner prev = byKey.put(key, s);
                if (prev != null) {
                    deduped++;
                    if (notes.size() < 40) notes.add("duplicate location collapsed: " + key.toLegacyString());
                }
            }
        }

        List<OwnedSpawner> deduplicated = new ArrayList<>(byKey.values());

        File backup = writeBackup(parsed, rowsRead, deduped, badLoc, badOwner);

        int imported = store.insertAllReplacing(deduplicated);

        return new Report(rowsRead, imported, deduped, badLoc, badOwner, notes, backup);
    }

    private File writeBackup(List<OwnedSpawner> rows, int read, int deduped, int badLoc, int badOwner) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("created", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        root.put("source", "mysql mspawners");
        root.put("rowsRead", read);
        root.put("duplicatesCollapsed", deduped);
        root.put("badLocations", badLoc);
        root.put("unparseableOwners", badOwner);
        List<Map<String, Object>> list = new ArrayList<>();
        for (OwnedSpawner s : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", s.key().world());
            m.put("x", s.key().x());
            m.put("y", s.key().y());
            m.put("z", s.key().z());
            m.put("type", s.entityType());
            m.put("owner", s.owner() == null ? null : s.owner().toString());
            list.add(m);
        }
        root.put("spawners", list);

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
        File out = new File(dataFolder, "migration-backup-" + stamp + ".json");
        Files.writeString(out.toPath(), gson.toJson(root), StandardCharsets.UTF_8);
        return out;
    }

    /**
     * Open the MySQL connection. Tries the normal classpath first; if no driver
     * is there, loads any jar dropped in {@code plugins/SoulboundSpawners/driver/}
     * and uses it directly (no DriverManager cross-classloader trouble).
     */
    private Connection connect(LegacyDatabaseConfig cfg) throws Exception {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("user", cfg.username());
        props.setProperty("password", cfg.password());

        for (String cn : new String[]{"com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver", "org.mariadb.jdbc.Driver"}) {
            try {
                Class.forName(cn);
                return DriverManager.getConnection(cfg.jdbcUrl(), cfg.username(), cfg.password());
            } catch (ClassNotFoundException ignored) {
            }
        }

        File driverDir = new File(dataFolder, "driver");
        File[] jars = driverDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jars != null && jars.length > 0) {
            URL[] urls = new URL[jars.length];
            for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toURI().toURL();
            URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader());
            for (Driver d : java.util.ServiceLoader.load(Driver.class, loader)) {
                Connection c = d.connect(cfg.jdbcUrl(), props);
                if (c != null) return c;
            }
        }

        throw new IllegalStateException("No MySQL/MariaDB JDBC driver found. Download mysql-connector-j "
                + "(e.g. from Maven Central) and drop the jar into "
                + new File(dataFolder, "driver") + " , then run /sbs migrate confirm again.");
    }
}
