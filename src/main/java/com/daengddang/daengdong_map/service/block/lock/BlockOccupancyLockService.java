package com.daengddang.daengdong_map.service.block.lock;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BlockOccupancyLockService {

    private static final String ACQUIRED_SENTINEL = "__ACQUIRED__";
    private static final String ALREADY_OWNED_SENTINEL = "__ALREADY_OWNED__";

    private static final String ACQUIRE_OR_TAKE_OVER = String.join("\n",
            "local current = redis.call('GET', KEYS[1])",
            "if not current then",
            "    redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])",
            "    return '" + ACQUIRED_SENTINEL + "'",
            "end",
            "if current == ARGV[1] then",
            "    redis.call('EXPIRE', KEYS[1], ARGV[2])",
            "    return '" + ALREADY_OWNED_SENTINEL + "'",
            "end",
            "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])",
            "return current"
    );

    private static final String COMPARE_AND_DELETE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    private static final String COMPARE_AND_EXPIRE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """;

    private final RedissonClient redissonClient;
    private final BlockOccupancyLockProperties properties;
    private final BlockOccupancyLockKeyFactory keyFactory;

    public BlockOccupancyLockResult tryAcquire(Long blockId, String ownerToken) {
        String key = keyFactory.lockKey(blockId);
        String result = script().eval(
                RScript.Mode.READ_WRITE,
                ACQUIRE_OR_TAKE_OVER,
                RScript.ReturnType.VALUE,
                List.of(key),
                ownerToken,
                String.valueOf(properties.getTtlSeconds())
        );

        if (ACQUIRED_SENTINEL.equals(result)) {
            return BlockOccupancyLockResult.acquired();
        }

        if (ALREADY_OWNED_SENTINEL.equals(result)) {
            return BlockOccupancyLockResult.alreadyOwned();
        }

        return BlockOccupancyLockResult.taken(result);
    }

    public boolean refresh(Long blockId, String ownerToken) {
        String key = keyFactory.lockKey(blockId);
        Number result = script().eval(
                RScript.Mode.READ_WRITE,
                COMPARE_AND_EXPIRE,
                RScript.ReturnType.INTEGER,
                List.of(key),
                ownerToken,
                String.valueOf(properties.getTtlSeconds())
        );
        return result != null && result.longValue() == 1L;
    }

    public boolean release(Long blockId, String ownerToken) {
        String key = keyFactory.lockKey(blockId);
        Number result = script().eval(
                RScript.Mode.READ_WRITE,
                COMPARE_AND_DELETE,
                RScript.ReturnType.INTEGER,
                List.of(key),
                ownerToken
        );
        return result != null && result.longValue() == 1L;
    }

    public String getOwnerToken(Long blockId) {
        return redissonClient.<String>getBucket(keyFactory.lockKey(blockId), StringCodec.INSTANCE).get();
    }

    public String buildOwnerToken(Long dogId, Long walkId) {
        return "dog:" + dogId + ":walk:" + walkId;
    }

    public Long extractDogId(String ownerToken) {
        if (ownerToken == null || ownerToken.isBlank()) {
            return null;
        }
        String[] parts = ownerToken.split(":");
        if (parts.length < 4 || !"dog".equals(parts[0])) {
            return null;
        }
        try {
            return Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private RScript script() {
        return redissonClient.getScript(StringCodec.INSTANCE);
    }
}
