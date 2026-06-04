package com.daengddang.daengdong_map.service.block.lock;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BlockOccupancyLockKeyFactory {

    private static final String SEP = ":";

    private final BlockOccupancyLockProperties properties;

    public String lockKey(Long blockId) {
        return prefix() + SEP + blockId;
    }

    private String prefix() {
        return properties.getKeyVersion() + SEP + properties.getKey();
    }
}
