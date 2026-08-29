package evo.soulboundspawners.storage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Where {@code /sbs migrate} reads the old spawner data from.
 *
 * <p>Normally auto-discovered from {@code plugins/AtherialLibPlugin/database.yml}
 * ({@link #discover}), so nothing needs typing in. For testing, or migrating
 * from a restored copy, the {@code migration:} section of config.yml overrides it
 * ({@link #explicit}).
 */
public record LegacyDatabaseConfig(String jdbcUrl, String username, String password,
                                   String database, String table, String source) {

    private static final String PARAMS =
            "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=60000";

    public static LegacyDatabaseConfig discover(File pluginsFolder) {
        File f = new File(pluginsFolder, "AtherialLibPlugin/database.yml");
        if (!f.isFile()) return null;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);

        String driver = yml.getString("driver", "mysql");
        if (driver != null && driver.equalsIgnoreCase("lite")) {
            return null; // SQLite source is not something we migrate from here
        }

        String database = yml.getString("database", "atherial");
        String host = yml.getString("host.address", "localhost");
        int port = yml.getInt("host.port", 3306);
        String user = yml.getString("auth.username", "");
        String pass = yml.getString("auth.password", "");

        return new LegacyDatabaseConfig(
                "jdbc:mysql://" + host + ":" + port + "/" + database + PARAMS,
                user, pass, database, "mspawners",
                "AtherialLibPlugin/database.yml (" + host + "/" + database + ")");
    }

    public static LegacyDatabaseConfig explicit(String host, int port, String database,
                                                String user, String pass, String table) {
        String t = (table == null || table.isBlank()) ? "mspawners" : table.trim();
        return new LegacyDatabaseConfig(
                "jdbc:mysql://" + host + ":" + port + "/" + database + PARAMS,
                user == null ? "" : user, pass == null ? "" : pass, database, t,
                "config migration: section (" + host + "/" + database + ")");
    }
}
