package evo.soulboundspawners.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Optional MarriageMaster integration, reached entirely by reflection so there's
 * no compile-time dependency. Lets a spawner owner's married partner(s) use
 * their soulbound spawners (config {@code soulmate}).
 *
 * <p>MarriageMaster's Bukkit plugin class implements its own API interface, so
 * the plugin instance <em>is</em> the entry point.
 */
public final class MarriageHook {

    private final Logger log;
    private Object mm;                 // the MarriageMaster plugin
    private Method mGetPlayerData;     // MarriagePlayer getPlayerData(UUID)
    private Method mGetPartners;       // Collection<MarriagePlayer> getPartners()   [on MarriagePlayer]
    private Method mGetUuid;           // UUID getUUID()                             [on MarriagePlayer]
    private Method mGetPlayerOnline;   // Player getPlayerOnline()                   [on MarriagePlayer]

    public MarriageHook(Logger log) {
        this.log = log;
    }

    public boolean isAvailable() {
        return mm != null;
    }

    public void setup() {
        mm = null;
        Plugin p = Bukkit.getPluginManager().getPlugin("MarriageMaster");
        if (p == null || !p.isEnabled()) return;
        try {
            Class<?> mpClass = Class.forName("at.pcgamingfreaks.MarriageMaster.Bukkit.API.MarriagePlayer");
            mGetPlayerData = p.getClass().getMethod("getPlayerData", UUID.class);
            mGetPartners = mpClass.getMethod("getPartners");
            mGetUuid = mpClass.getMethod("getUUID");
            mGetPlayerOnline = mpClass.getMethod("getPlayerOnline");
            mm = p;
            log.info("[SoulboundSpawners] Hooked MarriageMaster - partners can use soulbound spawners.");
        } catch (ReflectiveOperationException e) {
            mm = null;
            log.warning("[SoulboundSpawners] MarriageMaster present but the hook failed: " + e);
        }
    }

    /** @return true if {@code candidate} is a married partner of {@code owner}. */
    public boolean arePartners(UUID owner, UUID candidate) {
        if (mm == null || owner == null || candidate == null || owner.equals(candidate)) return false;
        try {
            for (Object partner : partnersOf(owner)) {
                if (owner.equals(candidate)) continue;
                Object id = mGetUuid.invoke(partner);
                if (candidate.equals(id)) return true;
            }
        } catch (ReflectiveOperationException e) {
            // hook broke - fail closed (not a partner)
        }
        return false;
    }

    /** Online married partners of {@code owner}. */
    public List<Player> onlinePartners(UUID owner) {
        if (mm == null || owner == null) return List.of();
        List<Player> out = new ArrayList<>();
        try {
            for (Object partner : partnersOf(owner)) {
                Object online = mGetPlayerOnline.invoke(partner);
                if (online instanceof Player pl) out.add(pl);
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return out;
    }

    private Collection<?> partnersOf(UUID owner) throws ReflectiveOperationException {
        Object ownerData = mGetPlayerData.invoke(mm, owner);
        Object partners = mGetPartners.invoke(ownerData);
        return partners instanceof Collection<?> c ? c : List.of();
    }
}
