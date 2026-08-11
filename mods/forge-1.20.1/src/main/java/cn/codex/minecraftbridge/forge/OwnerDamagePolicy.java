package cn.codex.minecraftbridge.forge;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

final class OwnerDamagePolicy {
    private OwnerDamagePolicy() {
    }

    static boolean isOwnerDamage(
        @Nullable UUID ownerUuid,
        @Nullable UUID directEntityUuid,
        @Nullable UUID causingEntityUuid
    ) {
        return ownerUuid != null
            && (ownerUuid.equals(directEntityUuid) || ownerUuid.equals(causingEntityUuid));
    }

    static boolean isOwnerOrOwnedDragonDamage(
        @Nullable UUID ownerUuid,
        @Nullable UUID directEntityUuid,
        @Nullable UUID causingEntityUuid,
        boolean directEntityIsOwnedDragon,
        boolean causingEntityIsOwnedDragon
    ) {
        return ownerUuid != null && (
            isOwnerDamage(ownerUuid, directEntityUuid, causingEntityUuid)
                || directEntityIsOwnedDragon
                || causingEntityIsOwnedDragon
        );
    }
}
