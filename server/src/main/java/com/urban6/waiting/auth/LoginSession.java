package com.urban6.waiting.auth;

import com.urban6.waiting.member.Member;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;

/** 로그인 상태를 세션에 넣고 빼는 유일한 통로. 문자열 속성 키를 한 곳에 가둔다. */
public final class LoginSession {

    private static final String KEY = "LOGIN_MEMBER";

    private LoginSession() {}

    /**
     * 세션이 이미 있으면 ID를 바꿔 세션 고정 공격을 막는다.
     * {@code getSession(false)} 검사를 빼면 안 된다. 세션이 없을 때 {@code changeSessionId()}가 던진다.
     */
    public static void login(HttpServletRequest request, Member member) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        request.getSession().setAttribute(KEY, member);
    }

    public static Optional<Member> current(HttpServletRequest request) {
        // getSession()은 기본이 create=true라 방문자마다 세션이 생긴다.
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((Member) session.getAttribute(KEY));
    }

    public static void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
