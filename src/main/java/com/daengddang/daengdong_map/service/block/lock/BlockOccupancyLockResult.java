package com.daengddang.daengdong_map.service.block.lock;

public record BlockOccupancyLockResult(Type type, String existingOwnerToken) {

    public enum Type {
        ACQUIRED,
        ALREADY_OWNED,
        TAKEN
    }

    public static BlockOccupancyLockResult acquired() {
        return new BlockOccupancyLockResult(Type.ACQUIRED, null);
    }

    public static BlockOccupancyLockResult alreadyOwned() {
        return new BlockOccupancyLockResult(Type.ALREADY_OWNED, null);
    }

    public static BlockOccupancyLockResult taken(String existingOwnerToken) {
        return new BlockOccupancyLockResult(Type.TAKEN, existingOwnerToken);
    }
}
