package com.daengddang.daengdong_map.websocket;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.daengddang.daengdong_map.common.ErrorCode;
import com.daengddang.daengdong_map.common.exception.BaseException;
import com.daengddang.daengdong_map.domain.user.User;
import com.daengddang.daengdong_map.domain.user.UserStatus;
import com.daengddang.daengdong_map.repository.UserRepository;
import com.daengddang.daengdong_map.repository.WalkRepository;
import com.daengddang.daengdong_map.security.AuthUser;
import com.daengddang.daengdong_map.security.jwt.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class WebSocketChannelInterceptorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalkRepository walkRepository;

    @InjectMocks
    private WebSocketChannelInterceptor interceptor;

    @Test
    void connect_storesAuthenticatedUserInOriginalMessageAccessor() {
        Claims claims = org.mockito.Mockito.mock(Claims.class);
        User user = org.mockito.Mockito.mock(User.class);
        when(jwtTokenProvider.parseClaims("access-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("1");
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(user.getId()).thenReturn(1L);
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setNativeHeader("Authorization", "Bearer access-token");
        accessor.setLeaveMutable(true);
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        StompHeaderAccessor storedAccessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        assertThatCode(() -> {
            if (storedAccessor == null || storedAccessor.getUser() == null) {
                throw new AssertionError("Authenticated user was not stored");
            }
        }).doesNotThrowAnyException();
    }

    @Test
    void subscribe_allowsWalkOwner() {
        when(walkRepository.existsOwnedWalkByIdAndUserId(10L, 1L)).thenReturn(true);
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/walks/10", 1L);

        assertThatCode(() -> interceptor.preSend(message, null))
                .doesNotThrowAnyException();
    }

    @Test
    void subscribe_rejectsNonOwner() {
        when(walkRepository.existsOwnedWalkByIdAndUserId(10L, 2L)).thenReturn(false);
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/walks/10", 2L);

        assertForbidden(() -> interceptor.preSend(message, null));
    }

    @Test
    void subscribe_allowsAuthenticatedBlockTopic() {
        Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE, "/topic/blocks/-1_2", 1L);

        assertThatCode(() -> interceptor.preSend(message, null))
                .doesNotThrowAnyException();
    }

    @Test
    void send_allowsKnownDestinationWithoutOwnershipLookup() {
        Message<byte[]> message =
                stompMessage(StompCommand.SEND, "/app/walks/10/location", 1L);

        assertThatCode(() -> interceptor.preSend(message, null))
                .doesNotThrowAnyException();
        verify(walkRepository, never()).existsOwnedWalkByIdAndUserId(10L, 1L);
    }

    @Test
    void send_rejectsUnknownDestination() {
        Message<byte[]> message = stompMessage(StompCommand.SEND, "/app/unknown", 1L);

        assertForbidden(() -> interceptor.preSend(message, null));
    }

    @Test
    void subscribe_rejectsMissingAuthentication() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/blocks/0_0");
        Message<byte[]> message =
                MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    private Message<byte[]> stompMessage(
            StompCommand command,
            String destination,
            Long userId
    ) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setDestination(destination);
        AuthUser authUser = new AuthUser(userId, UserStatus.ACTIVE);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                authUser,
                null,
                authUser.getAuthorities()
        ));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
