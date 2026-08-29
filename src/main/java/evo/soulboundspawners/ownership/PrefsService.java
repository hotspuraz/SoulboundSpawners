package evo.soulboundspawners.ownership;

import evo.soulboundspawners.storage.SpawnerStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player "let my partner use my spawners" preferences. Default (no row) is
 * all-allowed; the global {@code soulmate.*} config still applies on top, so a
 * player toggle can only <i>restrict</i>, never grant beyond what the server allows.
 */
public final class PrefsService {

    private static final int MINE = 0, PLACE = 1, SPAWN = 2;

    private final SpawnerStore store;
    private final Map<UUID, boolean[]> cache = new ConcurrentHashMap<>();

    public PrefsService(SpawnerStore store) {
        this.store = store;
    }

    public void load() {
        cache.clear();
        if (store.isHealthy()) cache.putAll(store.loadAllPrefs());
    }

    private boolean[] get(UUID owner) {
        return cache.getOrDefault(owner, DEFAULT);
    }
    private static final boolean[] DEFAULT = {true, true, true};

    public boolean partnerMine(UUID owner)  { return get(owner)[MINE]; }
    public boolean partnerPlace(UUID owner) { return get(owner)[PLACE]; }
    public boolean partnerSpawn(UUID owner) { return get(owner)[SPAWN]; }

    public void setPartnerMine(UUID owner, boolean v)  { set(owner, MINE, v); }
    public void setPartnerPlace(UUID owner, boolean v) { set(owner, PLACE, v); }
    public void setPartnerSpawn(UUID owner, boolean v) { set(owner, SPAWN, v); }

    private void set(UUID owner, int idx, boolean v) {
        boolean[] cur = cache.getOrDefault(owner, DEFAULT).clone();
        cur[idx] = v;
        cache.put(owner, cur);
        store.upsertPref(owner, cur[MINE], cur[PLACE], cur[SPAWN]);
    }
}
