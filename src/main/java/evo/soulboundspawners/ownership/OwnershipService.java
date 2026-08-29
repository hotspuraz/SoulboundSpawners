package evo.soulboundspawners.ownership;

import evo.soulboundspawners.storage.SpawnerStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * In-memory index of tracked spawners, backed by {@link SpawnerStore}.
 *
 * <p>If the database can't be read on startup the service goes
 * <b>degraded</b>: {@link #isDegraded()} is true, the cache is empty, and
 * callers are expected to <i>not</i> enforce ownership (so owned spawners keep
 * working) and <i>not</i> register anything new. This is strictly safer than the
 * old behaviour, where a DB outage silently switched every soulbound spawner off.
 */
public final class OwnershipService {

    private final Logger log;
    private final SpawnerStore store;
    private final Map<BlockKey, OwnedSpawner> cache = new ConcurrentHashMap<>();
    private volatile boolean degraded = true;

    public OwnershipService(Logger log, SpawnerStore store) {
        this.log = log;
        this.store = store;
    }

    public void load() {
        cache.clear();
        if (!store.isHealthy()) {
            degraded = true;
            return;
        }
        for (OwnedSpawner s : store.loadAll()) {
            cache.put(s.key(), s);
        }
        degraded = !store.isHealthy();
        log.info("[SoulboundSpawners] Loaded " + cache.size() + " tracked spawners"
                + (degraded ? " (DEGRADED – DB unhealthy)" : "") + ".");
    }

    public boolean isDegraded() {
        return degraded || !store.isHealthy();
    }

    public int size() {
        return cache.size();
    }

    /** Read-only snapshot of every tracked spawner (for /sbs audit). */
    public java.util.List<OwnedSpawner> all() {
        return new java.util.ArrayList<>(cache.values());
    }

    public OwnedSpawner get(BlockKey key) {
        return cache.get(key);
    }

    public boolean isTracked(BlockKey key) {
        return cache.containsKey(key);
    }

    public void register(BlockKey key, String entityType, UUID owner) {
        if (isDegraded()) {
            log.warning("[SoulboundSpawners] Not registering spawner at " + key.toLegacyString()
                    + " – storage is degraded.");
            return;
        }
        OwnedSpawner existing = cache.get(key);
        long created = existing != null ? existing.createdAt() : System.currentTimeMillis();
        OwnedSpawner s = new OwnedSpawner(key, entityType, owner, created);
        cache.put(key, s);
        store.upsert(s);
    }

    /** Remove tracking. Returns the record that was removed, or null. */
    public OwnedSpawner unregister(BlockKey key) {
        OwnedSpawner removed = cache.remove(key);
        if (removed != null && !isDegraded()) {
            store.delete(key);
        }
        return removed;
    }

    public boolean setOwner(BlockKey key, UUID newOwner) {
        OwnedSpawner s = cache.get(key);
        if (s == null || isDegraded()) return false;
        OwnedSpawner updated = s.withOwner(newOwner);
        cache.put(key, updated);
        store.upsert(updated);
        return true;
    }

    public boolean setType(BlockKey key, String newType) {
        OwnedSpawner s = cache.get(key);
        if (s == null || isDegraded()) return false;
        OwnedSpawner updated = s.withType(newType);
        cache.put(key, updated);
        store.upsert(updated);
        return true;
    }
}
