package com.daengddang.daengdong_map.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.daengddang.daengdong_map.domain.user.UserStatus;
import com.daengddang.daengdong_map.domain.walk.Walk;
import com.daengddang.daengdong_map.domain.walk.WalkStatus;
import com.daengddang.daengdong_map.dto.websocket.common.WebSocketErrorReason;
import com.daengddang.daengdong_map.dto.websocket.inbound.LocationUpdatePayload;
import com.daengddang.daengdong_map.repository.WalkRepository;
import com.daengddang.daengdong_map.security.AuthUser;
import com.daengddang.daengdong_map.service.cache.BlockCacheStore;
import com.daengddang.daengdong_map.util.StayValidator;
import com.daengddang.daengdong_map.util.WalkEventPublisher;
import com.daengddang.daengdong_map.util.WalkPointWriter;
import com.daengddang.daengdong_map.util.WalkSessionValidator;
import java.security.Principal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class WalkWebSocketServiceTest {

    @Mock
    private WalkRepository walkRepository;

    @Mock
    private BlockOccupancyService blockOccupancyService;

    @Mock
    private WalkEventPublisher walkEventPublisher;

    @Mock
    private WalkPointWriter walkPointWriter;

    @Mock
    private StayValidator stayValidator;

    @Mock
    private BlockSyncService blockSyncService;

    @Mock
    private BlockCacheStore blockCacheStore;

    private WalkWebSocketService walkWebSocketService;

    @BeforeEach
    void setUp() {
        WalkSessionValidator walkSessionValidator = new WalkSessionValidator(walkRepository);
        walkWebSocketService = new WalkWebSocketService(
                walkSessionValidator,
                blockOccupancyService,
                walkEventPublisher,
                walkPointWriter,
                stayValidator,
                blockSyncService,
                blockCacheStore
        );
    }

    @Test
    void handleLocationUpdate_checksOwnedWalkOnce() {
        Long walkId = 10L;
        Long userId = 1L;
        Walk walk = Walk.builder()
                .startedAt(LocalDateTime.now())
                .status(WalkStatus.IN_PROGRESS)
                .build();
        LocationUpdatePayload invalidLocation =
                LocationUpdatePayload.from(999.0, 127.0, OffsetDateTime.now());

        when(walkRepository.findOwnedWalkByIdAndUserId(walkId, userId))
                .thenReturn(Optional.of(walk));

        walkWebSocketService.handleLocationUpdate(walkId, invalidLocation, principal(userId));

        verify(walkRepository, times(1)).findOwnedWalkByIdAndUserId(walkId, userId);
        verify(walkRepository, never()).existsOwnedWalkByIdAndUserId(walkId, userId);
        verify(walkEventPublisher).sendError(
                eq(walkId),
                eq(WebSocketErrorReason.INVALID_LOCATION.getMessage())
        );
        verifyNoInteractions(walkPointWriter, blockOccupancyService);
    }

    private Principal principal(Long userId) {
        AuthUser authUser = new AuthUser(userId, UserStatus.ACTIVE);
        return new UsernamePasswordAuthenticationToken(
                authUser,
                null,
                authUser.getAuthorities()
        );
    }
}
