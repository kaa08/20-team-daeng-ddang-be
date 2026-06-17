package com.daengddang.daengdong_map.util;

import com.daengddang.daengdong_map.common.ErrorCode;
import com.daengddang.daengdong_map.common.exception.BaseException;
import com.daengddang.daengdong_map.domain.walk.Walk;
import com.daengddang.daengdong_map.domain.walk.WalkStatus;
import com.daengddang.daengdong_map.repository.WalkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WalkSessionValidator {

    private final WalkRepository walkRepository;

    public Walk getOwnedWalkOrThrow(Long walkId, Long userId) {
        return walkRepository.findOwnedWalkByIdAndUserId(walkId, userId)
                .orElseThrow(() -> new BaseException(ErrorCode.FORBIDDEN));
    }

    public boolean isActive(Walk walk) {
        return walk != null && walk.getStatus() == WalkStatus.IN_PROGRESS;
    }

    public boolean isValidCoordinate(double lat, double lng) {
        return CoordinateValidator.isValidLatLng(lat, lng);
    }
}
