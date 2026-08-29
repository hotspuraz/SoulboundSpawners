package evo.soulboundspawners.storage;

import evo.soulboundspawners.ownership.BlockKey;
import evo.soulboundspawners.ownership.OwnedSpawner;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite-backed spawner store. One connection, one writer thread: every DB
 * operation is serialised on {@link #io}, which sidesteps SQLite's
 * "database is locked" behaviour and the shared-connection races that the old
 * AtherialLib layer suffered from.
 */
public final class SpawnerStore {

    private final Logger log;
    private final File file;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SoulboundSpawners-DB");
        t.setDaemon(true);
        return t;
    });

    private Connection connection;
    private volatile boolean healthy;

    public SpawnerStore(Logger log, File file) {
        this.log = log;
        this.file = file;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public File file() {
        return file;
    }

    /** Open + create schema. Blocks. Returns true on success. */
    public boolean open() {
        try {
            submit(() -> {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new SQLException("could not create " + parent);
                }
                connection = openSqlite("jdbc:sqlite:" + file.getAbsolutePath());
                try (Statement st = connection.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                    st.execute("PRAGMA busy_timeout=5000");
                    st.execute("""
                        CREATE TABLE IF NOT EXISTS spawners (
                          world       TEXT    NOT NULL,
                          x           INTEGER NOT NULL,
                          y           INTEGER NOT NULL,
                          z           INTEGER NOT NULL,
                          entity_type TEXT,
                          owner       TEXT,
                          created_at  INTEGER NOT NULL,
                          PRIMARY KEY (world, x, y, z)
                        )""");
                    st.execute("""
                        CREATE TABLE IF NOT EXISTS player_prefs (
                          uuid          TEXT    NOT NULL PRIMARY KEY,
                          partner_mine  INTEGER NOT NULL DEFAULT 1,
                          partner_place INTEGER NOT NULL DEFAULT 1,
                          partner_spawn INTEGER NOT NULL DEFAULT 1
                        )""");
                }
                return null;
            }).get(20, TimeUnit.SECONDS);
            healthy = true;
            return true;
        } catch (Exception e) {
            healthy = false;
            log.log(Level.SEVERE, "[SoulboundSpawners] Could not open the spawner database (" + file + "). "
                    + "Running in DEGRADED mode: ownership rules are suspended and no new spawners will be tracked "
                    + "until this is fixed and the plugin reloaded.", e);
            return false;
        }
    }

    /** Load every row. Blocks – only call during enable / reload. */
    public List<OwnedSpawner> loadAll() {
        try {
            return submit(() -> {
                List<OwnedSpawner> out = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT world,x,y,z,entity_type,owner,created_at FROM spawners");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        BlockKey key = new BlockKey(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                        String type = rs.getString(5);
                        UUID owner = null;
                        String o = rs.getString(6);
                        if (o != null && !o.isEmpty() && !o.equalsIgnoreCase("null")) {
                            try { owner = UUID.fromString(o); } catch (IllegalArgumentException ignored) {}
                        }
                        out.add(new OwnedSpawner(key, type, owner, rs.getLong(7)));
                    }
                }
                return out;
            }).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            healthy = false;
            log.log(Level.SEVERE, "[SoulboundSpawners] Failed to load spawners from the database – DEGRADED mode.", e);
            return new ArrayList<>();
        }
    }

    /** uuid -> [partnerMine, partnerPlace, partnerSpawn]. Only rows that differ from the all-true default. Blocks. */
    public java.util.Map<UUID, boolean[]> loadAllPrefs() {
        java.util.Map<UUID, boolean[]> out = new java.util.HashMap<>();
        try {
            return submit(() -> {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT uuid,partner_mine,partner_place,partner_spawn FROM player_prefs")) {
                    while (rs.next()) {
                        try {
                            out.put(UUID.fromString(rs.getString(1)),
                                    new boolean[]{rs.getInt(2) != 0, rs.getInt(3) != 0, rs.getInt(4) != 0});
                        } catch (IllegalArgumentException ignored) {}
                    }
                }
                return out;
            }).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.log(Level.WARNING, "[SoulboundSpawners] Failed to load player prefs.", e);
            return out;
        }
    }

    public CompletableFuture<Void> upsertPref(UUID uuid, boolean mine, boolean place, boolean spawn) {
        return run(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO player_prefs (uuid,partner_mine,partner_place,partner_spawn)
                    VALUES (?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET
                      partner_mine = excluded.partner_mine,
                      partner_place = excluded.partner_place,
                      partner_spawn = excluded.partner_spawn""")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, mine ? 1 : 0);
                ps.setInt(3, place ? 1 : 0);
                ps.setInt(4, spawn ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        }, "upsertPref " + uuid);
    }

    public CompletableFuture<Void> upsert(OwnedSpawner s) {
        return run(() -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO spawners (world,x,y,z,entity_type,owner,created_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT(world,x,y,z) DO UPDATE SET
                      entity_type = excluded.entity_type,
                      owner       = excluded.owner""")) {
                ps.setString(1, s.key().world());
                ps.setInt(2, s.key().x());
                ps.setInt(3, s.key().y());
                ps.setInt(4, s.key().z());
                ps.setString(5, s.entityType());
                ps.setString(6, s.owner() == null ? null : s.owner().toString());
                ps.setLong(7, s.createdAt());
                ps.executeUpdate();
            }
            return null;
        }, "upsert " + s.key());
    }

    public CompletableFuture<Void> delete(BlockKey key) {
        return run(() -> {
            try (PreparedStatement ps = connection.prepareStatement(
                    "DELETE FROM spawners WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, key.world());
                ps.setInt(2, key.x());
                ps.setInt(3, key.y());
                ps.setInt(4, key.z());
                ps.executeUpdate();
            }
            return null;
        }, "delete " + key);
    }

    /** Bulk insert used by the migration. Runs in one transaction. Blocks. */
    public int insertAllReplacing(List<OwnedSpawner> rows) throws Exception {
        return submit(() -> {
            int n = 0;
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO spawners (world,x,y,z,entity_type,owner,created_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT(world,x,y,z) DO UPDATE SET
                      entity_type = excluded.entity_type,
                      owner       = excluded.owner""")) {
                for (OwnedSpawner s : rows) {
                    ps.setString(1, s.key().world());
                    ps.setInt(2, s.key().x());
                    ps.setInt(3, s.key().y());
                    ps.setInt(4, s.key().z());
                    ps.setString(5, s.entityType());
                    ps.setString(6, s.owner() == null ? null : s.owner().toString());
                    ps.setLong(7, s.createdAt());
                    ps.addBatch();
                    n++;
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(auto);
            }
            // fold the WAL into the main .db file so it's a complete, backup-ready
            // file immediately after a migration rather than after the next restart
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (SQLException ignored) {
            }
            return n;
        }).get(120, TimeUnit.SECONDS);
    }

    public long count() {
        try {
            return submit(() -> {
                try (Statement st = connection.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM spawners")) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return -1;
        }
    }

    public void close() {
        io.execute(() -> {
            try {
                if (connection != null) {
                    try (Statement st = connection.createStatement()) {
                        st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (SQLException ignored) {}
                    connection.close();
                }
            } catch (SQLException ignored) {}
        });
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) io.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- internals ---

    /**
     * Open the SQLite connection. Uses whatever driver is on the classpath (the
     * {@code libraries:} entry, or one bundled by Paper); failing that, any jar
     * dropped in {@code plugins/SoulboundSpawners/driver/}.
     */
    private Connection openSqlite(String url) throws SQLException {
        try {
            return DriverManager.getConnection(url);
        } catch (SQLException first) {
            File driverDir = new File(file.getParentFile(), "driver");
            File[] jars = driverDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
            if (jars != null) {
                try {
                    URL[] urls = new URL[jars.length];
                    for (int i = 0; i < jars.length; i++) urls[i] = jars[i].toURI().toURL();
                    URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader());
                    for (Driver drv : ServiceLoader.load(Driver.class, loader)) {
                        if (drv.acceptsURL(url)) {
                            Connection c = drv.connect(url, new java.util.Properties());
                            if (c != null) return c;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            throw first;
        }
    }

    private interface SqlTask<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> submit(SqlTask<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        io.execute(() -> {
            try {
                f.complete(task.run());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    private CompletableFuture<Void> run(SqlTask<Void> task, String desc) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        io.execute(() -> {
            try {
                task.run();
                f.complete(null);
            } catch (Throwable t) {
                healthy = false;
                log.log(Level.SEVERE, "[SoulboundSpawners] DB write failed (" + desc + ")", t);
                f.completeExceptionally(t);
            }
        });
        return f;
    }
}
