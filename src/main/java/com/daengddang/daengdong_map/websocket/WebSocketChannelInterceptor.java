package com.daengddang.daengdong_map.websocket;

import com.daengddang.daengdong_map.common.ErrorCode;
import com.daengddang.daengdong_map.common.exception.BaseException;
import com.daengddang.daengdong_map.domain.user.User;
import com.daengddang.daengdong_map.domain.user.UserStatus;
import com.daengddang.daengdong_map.repository.WalkRepository;
import com.daengddang.daengdong_map.repository.UserRepository;
import com.daengddang.daengdong_map.security.AuthUser;
import com.daengddang.daengdong_map.security.AuthUserExtractor;
import com.daengddang.daengdong_map.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebSocketChannelInterceptor implements ChannelInterceptor {

    private static final Pattern WALK_TOPIC_PATTERN =
            Pattern.compile("^" + WebSocketDestinations.WALKS_PREFIX + "(\\d+)$");
    private static final Pattern BLOCK_TOPIC_PATTERN =
            Pattern.compile("^" + WebSocketDestinations.BLOCKS_PREFIX + "-?\\d+_-?\\d+$");
    private static final Pattern WALK_LOCATION_PATTERN =
            Pattern.compile("^" + WebSocketDestinations.APP_PREFIX + "/walks/(\\d+)/location$");

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final WalkRepository walkRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        return switch (command) {
            case CONNECT -> authenticate(message, accessor);
            case SUBSCRIBE -> authorizeSubscribe(message, accessor);
            case SEND -> authorizeSend(message, accessor);
            default -> message;
        };
    }

    private Message<?> authenticate(Message<?> message, StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null) {
            authHeader = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION.toLowerCase());
        }

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BaseException(ErrorCode.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = jwtTokenProvider.parseClaims(token);
            Long userId = Long.valueOf(claims.getSubject());

            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new BaseException(ErrorCode.UNAUTHORIZED));

            if (user.getStatus() != UserStatus.ACTIVE) {
                throw new BaseException(ErrorCode.UNAUTHORIZED);
            }

            AuthUser authUser = new AuthUser(user.getId(), user.getStatus());
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            authUser,
                            null,
                            authUser.getAuthorities()
                    );

            accessor.setUser(authentication);
            return message;
        } catch (Exception e) {
            throw new BaseException(ErrorCode.UNAUTHORIZED);
        }
    }

    private Message<?> authorizeSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        Long userId = getUserId(accessor);
        String destination = requireDestination(accessor);

        Matcher walkMatcher = WALK_TOPIC_PATTERN.matcher(destination);
        if (walkMatcher.matches()) {
            authorizeWalkOwner(Long.valueOf(walkMatcher.group(1)), userId);
            return message;
        }

        if (BLOCK_TOPIC_PATTERN.matcher(destination).matches()) {
            return message;
        }

        throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private Message<?> authorizeSend(Message<?> message, StompHeaderAccessor accessor) {
        getUserId(accessor);
        String destination = requireDestination(accessor);

        if (WALK_LOCATION_PATTERN.matcher(destination).matches()) {
            return message;
        }

        throw new BaseException(ErrorCode.FORBIDDEN);
    }

    private Long getUserId(StompHeaderAccessor accessor) {
        return AuthUserExtractor.requireUserId(accessor.getUser());
    }

    private String requireDestination(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return destination;
    }

    private void authorizeWalkOwner(Long walkId, Long userId) {
        if (!walkRepository.existsOwnedWalkByIdAndUserId(walkId, userId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }
}
