package evo.soulboundspawners.storage;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

/**
 * Reads the MySQL connection details the old stack used, straight out of
 * {@code plugins/AtherialLibPlugin/database.yml}, so {@code /sbs migrate} needs
 * no credentials typed in.
 */
public record LegacyDatabaseConfig(String jdbcUrl, String username, String password, String database) {

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

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=60000";
        return new LegacyDatabaseConfig(url, user, pass, database);
    }
}
