package com.daengddang.daengdong_map.security;

import com.daengddang.daengdong_map.common.ErrorCode;
import com.daengddang.daengdong_map.common.exception.BaseException;
import java.security.Principal;
import org.springframework.security.core.Authentication;

public final class AuthUserExtractor {

    private AuthUserExtractor() {
    }

    public static Long requireUserId(Principal principal) {
        return requireAuthUser(principal).getUserId();
    }

    public static AuthUser requireAuthUser(Principal principal) {
        if (!(principal instanceof Authentication authentication)
                || !(authentication.getPrincipal() instanceof AuthUser authUser)) {
            throw new BaseException(ErrorCode.UNAUTHORIZED);
        }
        return authUser;
    }
}
