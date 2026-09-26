package com.urban6.waiting.auth;

import com.urban6.waiting.auth.PassCookie.Pass;
import com.urban6.waiting.queue.WaitingQueueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 입장권 게이트. 쿠키는 클라이언트가 지어낼 수 있어서 매번 Redis active ZSet에 살아 있는지 확인한다.
 *
 * <p>예약 시간 만료도 여기서 걸린다. 로그인 시점에 슬롯 만료가 reservation-ttl로 다시 찍히기 때문에
 * 만료 전용 인터셉터를 따로 두지 않는다.
 */
@Component
@RequiredArgsConstructor
public class AdmissionGuard implements HandlerInterceptor {

    static final String REDIRECT = "/?reason=admission-required";

    /** 로그인까지 마쳤는데 예약 시간이 끝났다. */
    static final String EXPIRED_REDIRECT = "/?reason=reservation-expired";

    /** 통과한 요청에 남기는 활성 만료 시각(epoch ms). */
    public static final String EXPIRES_AT = AdmissionGuard.class.getName() + ".expiresAt";

    private final WaitingQueueService waitingQueueService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Optional<Pass> pass = PassCookie.read(request);

        if (pass.isPresent()) {
            OptionalLong expiresAt = waitingQueueService.activeUntil(pass.get().date(), pass.get().token());
            if (expiresAt.isPresent()) {
                request.setAttribute(EXPIRES_AT, expiresAt.getAsLong());
                return true;
            }
        }

        // 세션을 버리기 전에 읽어야 만료와 미입장을 구분해 안내할 수 있다.
        String redirect = LoginSession.current(request).isPresent() ? EXPIRED_REDIRECT : REDIRECT;

        LoginSession.logout(request);
        response.addHeader("Set-Cookie", ExpiredCookies.pass());
        response.sendRedirect(redirect);
        return false;
    }
}
