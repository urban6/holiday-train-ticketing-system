package com.urban6.waiting.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 로그인하지 않았으면 로그인 화면으로 보낸다. {@link AdmissionGuard} 다음에 돈다. */
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
