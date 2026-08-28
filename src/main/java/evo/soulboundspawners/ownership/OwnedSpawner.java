package evo.soulboundspawners.ownership;

import java.util.UUID;

/** A tracked, owned spawner. */
public record OwnedSpawner(BlockKey key, String entityType, UUID owner, long createdAt) {

    public OwnedSpawner withOwner(UUID newOwner) {
        return new OwnedSpawner(key, entityType, newOwner, createdAt);
    }

    public OwnedSpawner withType(String newType) {
        return new OwnedSpawner(key, newType, owner, createdAt);
    }
}
