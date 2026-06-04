package com.daengddang.daengdong_map.service;

import com.daengddang.daengdong_map.domain.block.Block;
import com.daengddang.daengdong_map.domain.block.BlockOwnership;
import com.daengddang.daengdong_map.domain.dog.Dog;
import com.daengddang.daengdong_map.domain.walk.Walk;
import com.daengddang.daengdong_map.repository.BlockOwnershipRepository;
import com.daengddang.daengdong_map.repository.BlockRepository;
import com.daengddang.daengdong_map.repository.WalkBlockLogRepository;
import com.daengddang.daengdong_map.service.block.lock.BlockOccupancyLockProperties;
import com.daengddang.daengdong_map.service.block.lock.BlockOccupancyLockResult;
import com.daengddang.daengdong_map.service.block.lock.BlockOccupancyLockService;
import com.daengddang.daengdong_map.util.BlockOccupancyResult;
import com.daengddang.daengdong_map.util.WalkRuntimeStateRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BlockOccupancyService {

    private final BlockRepository blockRepository;
    private final BlockOwnershipRepository blockOwnershipRepository;
    private final WalkBlockLogRepository walkBlockLogRepository;
    private final BlockOccupancyLockProperties lockProperties;
    private final BlockOccupancyLockService lockService;
    private final WalkRuntimeStateRegistry walkRuntimeStateRegistry;

    @Transactional
    public BlockOccupancyResult occupy(Walk walk, int blockX, int blockY, LocalDateTime timestamp) {
        if (lockProperties.isEnabled()) {
            return occupyWithRedisLock(walk, blockX, blockY, timestamp);
        }

        return occupyWithDatabaseOnly(walk, blockX, blockY, timestamp);
    }

    private BlockOccupancyResult occupyWithDatabaseOnly(Walk walk, int blockX, int blockY, LocalDateTime timestamp) {
        Dog dog = walk.getDog();

        Long blockId = blockRepository.insertIfNotExistsReturningId(blockX, blockY);
        Long ownerDogId = blockOwnershipRepository.findOwnerDogIdByBlockId(blockId).orElse(null);
        if (ownerDogId == null) {
            Block block = blockRepository.getReferenceById(blockId);
            BlockOwnership newOwnership = BlockOwnership.builder()
                    .block(block)
                    .dog(dog)
                    .acquiredAt(timestamp)
                    .lastPassedAt(timestamp)
                    .build();
            blockOwnershipRepository.save(newOwnership);
            walkBlockLogRepository.insertIfNotExists(walk.getId(), blockId, dog.getId(), null, timestamp);
            return BlockOccupancyResult.occupied();
        }

        if (ownerDogId.equals(dog.getId())) {
            blockOwnershipRepository.touchLastPassedAt(blockId, dog.getId(), timestamp);
            return BlockOccupancyResult.alreadyOwned();
        }

        Long previousDogId = ownerDogId;
        blockOwnershipRepository.updateOwnerByBlockId(blockId, dog.getId(), timestamp);
        walkBlockLogRepository.insertIfNotExists(walk.getId(), blockId, dog.getId(), previousDogId, timestamp);
        return BlockOccupancyResult.taken(previousDogId);
    }

    private BlockOccupancyResult occupyWithRedisLock(Walk walk, int blockX, int blockY, LocalDateTime timestamp) {
        Dog dog = walk.getDog();
        Long blockId = blockRepository.insertIfNotExistsReturningId(blockX, blockY);
        String ownerToken = lockService.buildOwnerToken(dog.getId(), walk.getId());
        if (canSkipHeartbeatRefresh(walk.getId(), blockId, ownerToken, timestamp)) {
            blockOwnershipRepository.touchLastPassedAt(blockId, dog.getId(), timestamp);
            return BlockOccupancyResult.alreadyOwned();
        }

        BlockOccupancyLockResult lockResult = lockService.tryAcquire(blockId, ownerToken);
        Long ownerDogId = blockOwnershipRepository.findOwnerDogIdByBlockId(blockId).orElse(null);

        return switch (lockResult.type()) {
            case ACQUIRED -> {
                recordLockHeartbeat(walk.getId(), blockId, timestamp);
                yield applyOwnershipAfterAcquire(walk, dog, blockId, ownerDogId, timestamp);
            }
            case ALREADY_OWNED -> {
                recordLockHeartbeat(walk.getId(), blockId, timestamp);
                blockOwnershipRepository.touchLastPassedAt(blockId, dog.getId(), timestamp);
                yield BlockOccupancyResult.alreadyOwned();
            }
            case TAKEN -> {
                recordLockHeartbeat(walk.getId(), blockId, timestamp);
                Long previousDogId = lockService.extractDogId(lockResult.existingOwnerToken());
                if (previousDogId == null && ownerDogId != null && !ownerDogId.equals(dog.getId())) {
                    previousDogId = ownerDogId;
                }
                blockOwnershipRepository.updateOwnerByBlockId(blockId, dog.getId(), timestamp);
                walkBlockLogRepository.insertIfNotExists(walk.getId(), blockId, dog.getId(), previousDogId, timestamp);
                yield BlockOccupancyResult.taken(previousDogId);
            }
        };
    }

    private BlockOccupancyResult applyOwnershipAfterAcquire(
            Walk walk,
            Dog dog,
            Long blockId,
            Long ownerDogId,
            LocalDateTime timestamp
    ) {
        if (ownerDogId == null) {
            Block block = blockRepository.getReferenceById(blockId);
            BlockOwnership newOwnership = BlockOwnership.builder()
                    .block(block)
                    .dog(dog)
                    .acquiredAt(timestamp)
                    .lastPassedAt(timestamp)
                    .build();
            blockOwnershipRepository.save(newOwnership);
            walkBlockLogRepository.insertIfNotExists(walk.getId(), blockId, dog.getId(), null, timestamp);
            return BlockOccupancyResult.occupied();
        }

        if (ownerDogId.equals(dog.getId())) {
            blockOwnershipRepository.touchLastPassedAt(blockId, dog.getId(), timestamp);
            return BlockOccupancyResult.alreadyOwned();
        }

        blockOwnershipRepository.updateOwnerByBlockId(blockId, dog.getId(), timestamp);
        walkBlockLogRepository.insertIfNotExists(walk.getId(), blockId, dog.getId(), ownerDogId, timestamp);
        return BlockOccupancyResult.taken(ownerDogId);
    }

    private boolean canSkipHeartbeatRefresh(Long walkId, Long blockId, String ownerToken, LocalDateTime timestamp) {
        String currentOwnerToken = lockService.getOwnerToken(blockId);
        if (!ownerToken.equals(currentOwnerToken)) {
            return false;
        }

        WalkRuntimeStateRegistry.LockHeartbeatState heartbeatState = walkRuntimeStateRegistry.getLockHeartbeatState(walkId);
        if (heartbeatState == null || !blockId.equals(heartbeatState.getBlockId())) {
            return false;
        }

        long intervalSeconds = lockProperties.getHeartbeatIntervalSeconds();
        if (intervalSeconds <= 0) {
            return false;
        }

        Duration elapsed = Duration.between(heartbeatState.getLastRefreshedAt(), timestamp);
        return elapsed.getSeconds() < intervalSeconds;
    }

    private void recordLockHeartbeat(Long walkId, Long blockId, LocalDateTime timestamp) {
        walkRuntimeStateRegistry.putLockHeartbeatState(
                walkId,
                new WalkRuntimeStateRegistry.LockHeartbeatState(blockId, timestamp)
        );
    }
}
