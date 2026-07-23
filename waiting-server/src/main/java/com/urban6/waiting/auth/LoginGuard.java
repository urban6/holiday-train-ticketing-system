package com.urban6.waiting.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 로그인하지 않았으면 로그인 화면으로 보낸다.
 *
 * <p>{@link AdmissionGuard} 다음에 돈다. 순서가 중요하다 — 입장권이 없는 사람을
 * 로그인 화면으로 보내면 거기서 다시 AdmissionGuard에 걸려 랜딩으로 튕긴다.
 * 두 번 튕기는 대신 한 번에 랜딩까지 보내려면 입장권을 먼저 봐야 한다.
 */
@Component
public class LoginGuard implements HandlerInterceptor {

    static final String REDIRECT = "/login";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (LoginSession.current(request).isPresent()) {
            return true;
        }

        response.sendRedirect(REDIRECT);
        return false;
    }
}
