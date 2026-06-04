package com.daengddang.daengdong_map.service.block.lock;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "block.occupancy.lock")
public class BlockOccupancyLockProperties {

    private boolean enabled = false;
    private long ttlSeconds = 30L;
    private long heartbeatIntervalSeconds = 10L;
    private String key = "block:occupancy:lock";
    private String keyVersion = "v3";
}
