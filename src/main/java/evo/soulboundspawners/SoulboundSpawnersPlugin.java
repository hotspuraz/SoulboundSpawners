package evo.soulboundspawners;

import evo.soulboundspawners.command.SbsCommand;
import evo.soulboundspawners.config.LegacyConfigImport;
import evo.soulboundspawners.config.PluginConfig;
import evo.soulboundspawners.hook.InsightsHook;
import evo.soulboundspawners.hook.MarriageHook;
import evo.soulboundspawners.hook.SellGuardListener;
import evo.soulboundspawners.hook.VaultHook;
import evo.soulboundspawners.listener.AnvilRenameListener;
import evo.soulboundspawners.listener.JoinItemFixListener;
import evo.soulboundspawners.listener.SpawnerEggListener;
import evo.soulboundspawners.listener.SpawnerMineListener;
import evo.soulboundspawners.listener.SpawnerPlaceListener;
import evo.soulboundspawners.listener.SpawnerProtectionListener;
import evo.soulboundspawners.listener.SpawnerSpawnListener;
import evo.soulboundspawners.ownership.OwnershipService;
import evo.soulboundspawners.ownership.PrefsService;
import evo.soulboundspawners.spawner.SoulboundTypes;
import evo.soulboundspawners.spawner.SpawnerItems;
import evo.soulboundspawners.storage.SpawnerStore;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

public final class SoulboundSpawnersPlugin extends JavaPlugin {

    private PluginConfig config;
    private SoulboundTypes soulboundTypes;
    private SpawnerItems spawnerItems;
    private SpawnerStore store;
    private OwnershipService ownership;
    private PrefsService prefs;
    private final VaultHook vault = new VaultHook(getLogger());
    private final InsightsHook insights = new InsightsHook(getLogger());
    private final MarriageHook marriage = new MarriageHook(getLogger());
    private final Permissions perms = new Permissions();
    private SpawnerMineListener mineListener;
    private SellGuardListener sellGuard;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        LegacyConfigImport.run(pluginsFolder(), getDataFolder(), getConfig(), getLogger());
        // add any config keys introduced in a newer version (existing values kept)
        getConfig().options().copyDefaults(true);
        saveConfig();

        buildConfigDerived();

        store = new SpawnerStore(getLogger(), new File(getDataFolder(), config.storageFileName()));
        store.open();

        ownership = new OwnershipService(getLogger(), store);
        ownership.load();
        prefs = new PrefsService(store);
        prefs.load();

        vault.setup();
        insights.setup();
        marriage.setup();

        registerListeners();
        registerCommand();

        getLogger().info("SoulboundSpawners " + getPluginMeta().getVersion() + " enabled – "
                + ownership.size() + " tracked spawners, "
                + soulboundTypes.names().size() + " soulbound types"
                + (ownership.isDegraded() ? ", DEGRADED (storage)" : "") + ".");
    }

    @Override
    public void onDisable() {
        if (store != null) store.close();
    }

    private void buildConfigDerived() {
        config = new PluginConfig(getConfig());
        soulboundTypes = new SoulboundTypes(config.soulboundTypes());
        spawnerItems = new SpawnerItems(this, config, soulboundTypes);
        perms.setLegacyEnabled(getConfig().getBoolean("legacy-permissions", true));
    }

    private void registerListeners() {
        mineListener = new SpawnerMineListener(this);
        getServer().getPluginManager().registerEvents(mineListener, this);
        getServer().getPluginManager().registerEvents(new SpawnerPlaceListener(this), this);
        getServer().getPluginManager().registerEvents(new SpawnerSpawnListener(this), this);
        getServer().getPluginManager().registerEvents(new SpawnerProtectionListener(this), this);
        getServer().getPluginManager().registerEvents(new SpawnerEggListener(this), this);
        getServer().getPluginManager().registerEvents(new AnvilRenameListener(this), this);
        getServer().getPluginManager().registerEvents(new JoinItemFixListener(this), this);

        sellGuard = new SellGuardListener(this);
        if (getConfig().getBoolean("sell-guard.enabled", true)) {
            sellGuard.register(getConfig().getStringList("sell-guard.events"));
        }
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("soulboundspawners");
        if (cmd != null) {
            SbsCommand exec = new SbsCommand(this);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }
    }

    /** Reload config + re-derive services + reload the ownership cache. */
    public void reloadEverything() {
        reloadConfig();
        buildConfigDerived();
        if (mineListener != null) mineListener.clearCache();
        ownership.load();
        prefs.load();
    }

    // --- accessors ---

    public PluginConfig config() { return config; }
    public SoulboundTypes soulboundTypes() { return soulboundTypes; }
    public SpawnerItems spawnerItems() { return spawnerItems; }
    public SpawnerStore store() { return store; }
    public OwnershipService ownership() { return ownership; }
    public PrefsService prefs() { return prefs; }
    public VaultHook vault() { return vault; }
    public InsightsHook insights() { return insights; }
    public MarriageHook marriage() { return marriage; }
    public Permissions perms() { return perms; }

    public File pluginsFolder() {
        return getDataFolder().getParentFile();
    }

    public void send(CommandSender who, String raw) {
        if (raw == null || raw.isEmpty()) return;
        who.sendMessage(Text.color(raw));
    }

    public void send(org.bukkit.entity.HumanEntity who, String raw) {
        if (raw == null || raw.isEmpty()) return;
        who.sendMessage(Text.color(raw));
    }

    /**
     * In-world feedback (blocked / warned while interacting with a spawner).
     * Goes to the action bar for players so it can't spam chat, and is uniform
     * whichever listener sends it. Non-players fall back to chat.
     */
    public void notify(CommandSender who, String raw) {
        if (raw == null || raw.isEmpty()) return;
        if (who instanceof org.bukkit.entity.Player p) p.sendActionBar(Text.color(raw));
        else who.sendMessage(Text.color(raw));
    }

    public void send(CommandSender who, Component component) {
        who.sendMessage(component);
    }
}
