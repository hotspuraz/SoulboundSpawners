package evo.soulboundspawners.spawner;

import org.bukkit.entity.EntityType;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The set of entity types that are "soulbound" – i.e. spawners of this type get
 * per-player ownership and the owner-must-be-nearby spawn rule. Sourced from
 * {@code soulbound.types} in config (originally {@code soulbound:} in newC.yml).
 */
public final class SoulboundTypes {

    private final Set<String> names = new HashSet<>();

    public SoulboundTypes(Iterable<String> configured) {
        for (String s : configured) {
            if (s != null && !s.isBlank()) names.add(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    public boolean isSoulbound(String entityName) {
        return entityName != null && names.contains(entityName.toUpperCase(Locale.ROOT));
    }

    public boolean isSoulbound(EntityType type) {
        return type != null && names.contains(type.name());
    }

    public Set<String> names() {
        return java.util.Collections.unmodifiableSet(names);
    }
}
