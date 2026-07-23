package com.urban6.waiting.auth;

import com.urban6.waiting.member.Member;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;

/**
 * 로그인 상태를 세션에 넣고 빼는 유일한 통로.
 *
 * <p>세션 속성 키는 문자열이라 오타가 컴파일에 안 걸린다. 여러 곳에 흩어 놓으면
 * 한쪽에서 넣고 다른 쪽에서 못 읽는 버그가 조용히 생기므로 여기 한 곳에 가둔다.
 *
 * <p>지금 세션은 톰캣 인메모리다. WAS를 다중화하면 인스턴스마다 세션이 따로 놀아
 * 로그인이 요청마다 풀리는데, 그때 {@code spring-session-data-redis}로 옮기면
 * 바꿀 곳이 이 클래스 바깥에는 없다.
 */
public final class LoginSession {

    private static final String KEY = "LOGIN_MEMBER";

    private LoginSession() {}

    /**
     * 로그인 성공 시점에 부른다.
     *
     * <p>세션이 이미 있으면 ID를 갈아 끼운다. 공격자가 피해자 브라우저에 심어 둔 세션 ID를
     * 그대로 들고 로그인이 끝나면, 공격자가 그 ID로 남의 로그인 세션에 올라탈 수 있다
     * (세션 고정 공격). ID를 바꾸면 미리 심어 둔 값이 무효가 된다.
     *
     * <p><b>{@code getSession(false)} 검사를 빼면 안 된다.</b>
     * {@code changeSessionId()}는 세션이 없으면 IllegalStateException을 던진다.
     * 이 앱에는 로그인 전에 세션을 만드는 경로가 없어서(로그인 폼 GET도 세션을 건드리지 않는다)
     * 검사가 없으면 <b>정상 로그인이 항상</b> 500으로 끝난다.
     *
     * <p>세션이 없는 경우는 아래 {@code getSession()}이 새로 만든다. 새 세션은 애초에
     * 서버가 방금 만든 ID라 공격자가 미리 알 수 없으므로, 갈아 끼울 이유도 없다.
     * (공격자가 심은 ID가 서버에 없는 세션이면 톰캣이 그 값을 무시하고 새 ID를 발급한다.)
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
