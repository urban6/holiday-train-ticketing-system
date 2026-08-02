package com.urban6.waiting.auth;

import com.urban6.waiting.member.Member;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;

/**
 * 로그인 상태를 세션에 넣고 빼는 유일한 통로.
 *
 * <p>세션 속성 키는 문자열이라 오타가 컴파일에 안 걸린다. 흩어 놓으면 한쪽에서 넣고 다른 쪽에서
 * 못 읽는 버그가 조용히 생기므로 여기 한 곳에 가둔다.
 *
 * <p>지금은 톰캣 인메모리다. WAS 다중화 시 {@code spring-session-data-redis}로 옮기면
 * 바꿀 곳이 이 클래스 바깥에는 없다.
 */
public final class LoginSession {

    private static final String KEY = "LOGIN_MEMBER";

    private LoginSession() {}

    /**
     * 로그인 성공 시점에 부른다. 세션이 이미 있으면 ID를 갈아 끼워 세션 고정 공격을 막는다 —
     * 공격자가 미리 심어 둔 ID가 무효가 된다.
     *
     * <p><b>{@code getSession(false)} 검사를 빼면 안 된다.</b> {@code changeSessionId()}는
     * 세션이 없으면 IllegalStateException을 던지는데, 이 앱에는 로그인 전에 세션을 만드는 경로가
     * 없어서 검사가 없으면 <b>정상 로그인이 항상</b> 500으로 끝난다.
     */
    public static void login(HttpServletRequest request, Member member) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        request.getSession().setAttribute(KEY, member);
    }

    public static Optional<Member> current(HttpServletRequest request) {
        // 여기서 세션을 새로 만들면 안 된다. getSession()은 기본이 create=true라
        // 로그인하지 않은 방문자에게도 세션이 생기고 JSESSIONID가 내려간다.
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((Member) session.getAttribute(KEY));
    }

    public static void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 속성만 지우면 세션 껍데기가 timeout까지 남는다. 통째로 버린다.
            session.invalidate();
        }
    }
}
