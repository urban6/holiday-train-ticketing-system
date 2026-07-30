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
 * 입장권 게이트. 대기열을 실제로 통과한 사람만 지나간다.
 *
 * <p>이게 없으면 주소창에 /login을 직접 쳐서 대기열을 통째로 건너뛸 수 있다.
 *
 * <p><b>쿠키가 있다는 것만으로는 부족하다.</b> 클라이언트가 지어낼 수 있는 값이라, 매번 Redis의
 * active ZSet에 살아 있는지 확인해야 정원과 만료가 실제로 강제된다.
 *
 * <p>예약 시간(queue.reservation-ttl)도 여기서 함께 끝난다 — 로그인 시점에 슬롯 만료가 그 값으로
 * 다시 찍히므로 이 검사가 저절로 실패한다. 만료 전용 인터셉터를 따로 두지 않은 이유다.
 */
@Component
@RequiredArgsConstructor
public class AdmissionGuard implements HandlerInterceptor {

    /** 대기열을 거치지 않았거나 입장권을 쓰지 못했다. */
    static final String REDIRECT = "/?reason=admission-required";

    /** 로그인까지 마쳤는데 예약 시간이 끝났다. 사용자 입장에서 원인이 전혀 다르므로 나눈다. */
    static final String EXPIRED_REDIRECT = "/?reason=reservation-expired";

    /** 통과한 요청에 남기는 활성 만료 시각(epoch ms). 예약 화면이 남은 시간을 그릴 때 쓴다. */
    public static final String EXPIRES_AT = AdmissionGuard.class.getName() + ".expiresAt";

    private final WaitingQueueService waitingQueueService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        Optional<Pass> pass = PassCookie.read(request);

        if (pass.isPresent()) {
            OptionalLong expiresAt = waitingQueueService.activeUntil(pass.get().windowId(), pass.get().token());
            if (expiresAt.isPresent()) {
                request.setAttribute(EXPIRES_AT, expiresAt.getAsLong());
                return true;
            }
        }

        // 세션을 버리기 전에 읽는다. 순서가 뒤집히면 예약 시간을 다 쓴 사람에게도
        // "대기열을 거치지 않았습니다"로 안내하게 된다.
        String redirect = LoginSession.current(request).isPresent() ? EXPIRED_REDIRECT : REDIRECT;

        // 입장권이 죽었으면 로그인도 의미가 없다. 세션만 남으면 다음에 로그인 화면이
        // 이미 로그인된 상태로 떠서 화면과 실제가 어긋난다.
        LoginSession.logout(request);

        // 만료된 쿠키를 두면 다음 요청에서도 똑같이 Redis를 한 번 더 친다.
        response.addHeader("Set-Cookie", ExpiredCookies.pass());
        response.sendRedirect(redirect);
        return false;
    }
}
