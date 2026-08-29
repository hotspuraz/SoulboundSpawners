package evo.soulboundspawners.hook;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Keeps Insights' per-chunk block count honest when we remove a spawner that
 * another plugin stopped from breaking normally.
 *
 * <p>Insights only decrements its cache on an <em>un-cancelled</em>
 * {@code BlockBreakEvent} (MONITOR). If a region flag / anticheat cancels that
 * event, our spawner still goes away but Insights never learns, so its per-chunk
 * limit stays stuck. When that happens we drop Insights' cached distribution for
 * the chunk (via reflection, no compile-time dependency) – Insights then
 * re-scans it on the next placement and gets the true count. Any reflection
 * failure just disables the hook.
 */
public final class InsightsHook {

    private final Logger log;
    private boolean active;

    private Object insights;         // InsightsPlugin
    private Method getWorldStorage;  // InsightsMain#getWorldStorage()
    private Method getWorld;         // WorldStorage#getWorld(UUID) -> ChunkStorage
    private Method chunkRemove;      // ChunkStorage#remove(long)

    public InsightsHook(Logger log) {
        this.log = log;
    }

    public boolean isActive() {
        return active;
    }

    public void setup() {
        active = false;
        if (Bukkit.getPluginManager().getPlugin("Insights") == null) return;
        try {
            Class<?> pluginClass = Class.forName("dev.frankheijden.insights.api.InsightsPlugin");
            insights = pluginClass.getMethod("getInstance").invoke(null);
            if (insights == null) return;

            getWorldStorage = insights.getClass().getMethod("getWorldStorage");
            Object worldStorage = getWorldStorage.invoke(insights);
            getWorld = worldStorage.getClass().getMethod("getWorld", UUID.class);

            chunkRemove = Class.forName("dev.frankheijden.insights.api.concurrent.storage.ChunkStorage")
                    .getMethod("remove", long.class);

            active = true;
            log.info("[SoulboundSpawners] Insights present – its spawner counts will be kept in sync.");
        } catch (ReflectiveOperationException | RuntimeException e) {
            active = false;
            log.warning("[SoulboundSpawners] Insights hook could not initialise (" + e.getClass().getSimpleName()
                    + "); its spawner counts may drift if another plugin cancels spawner breaks.");
        }
    }

    /**
     * Call only when a spawner was removed <b>without</b> a normal break event
     * (i.e. our safety net had to force it) – otherwise Insights already counted it.
     */
    public void chunkChangedOutsideEvent(Block block) {
        if (!active) return;
        try {
            Object worldStorage = getWorldStorage.invoke(insights);
            Object chunkStorage = getWorld.invoke(worldStorage, block.getWorld().getUID());
            long chunkKey = ((long) (block.getX() >> 4) & 0xffffffffL)
                    | (((long) (block.getZ() >> 4) & 0xffffffffL) << 32);
            chunkRemove.invoke(chunkStorage, chunkKey);
        } catch (ReflectiveOperationException | RuntimeException e) {
            active = false;
            log.warning("[SoulboundSpawners] Insights hook failed mid-run, disabling it: " + e.getMessage());
        }
    }
}
