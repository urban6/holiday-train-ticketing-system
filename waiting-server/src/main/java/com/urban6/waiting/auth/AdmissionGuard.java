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
 * 지금까지 만든 유입 제어가 이 한 겹으로 완성된다.
 *
 * <p><b>쿠키가 있다는 것만으로는 부족하다.</b> 쿠키는 클라이언트가 들고 있는 값이라
 * 얼마든지 지어낼 수 있다. 매번 Redis의 active ZSet에 그 토큰이 살아 있는지 확인해야
 * 활성 정원과 만료가 실제로 강제된다. 화면 진입당 한 번이라 부담은 없다.
 *
 * <p>로그인 후 예약에 주어진 시간(queue.reservation-ttl)도 여기서 함께 끝난다.
 * 로그인 시점에 슬롯 만료가 그 값으로 다시 찍히므로 시간이 지나면 이 검사가 저절로 실패한다.
 * 만료 전용 인터셉터를 따로 두지 않은 이유다 — 판정의 진실 원천은 Redis 슬롯 하나뿐이다.
 */
@Component
@RequiredArgsConstructor
public class AdmissionGuard implements HandlerInterceptor {

    /** 대기열을 거치지 않았거나 입장권을 쓰지 못했다. */
    static final String REDIRECT = "/?reason=admission-required";

    /** 로그인까지 마쳤는데 예약 시간이 끝났다. 사용자 입장에서 원인이 전혀 다르므로 나눈다. */
    static final String EXPIRED_REDIRECT = "/?reason=reservation-expired";

    /**
     * 통과한 요청에 남기는 활성 만료 시각(epoch ms).
     * 예약 화면이 남은 시간을 그릴 때 쓴다 — 같은 값을 Redis에 다시 묻지 않기 위해서다.
     */
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

        // 로그인 상태였는지를 세션을 버리기 전에 읽는다. 순서가 뒤집히면 언제나 "대기열을
        // 거치지 않았습니다"로 안내하게 되는데, 3분을 다 쓴 사람에게는 사실이 아니다.
        String redirect = LoginSession.current(request).isPresent() ? EXPIRED_REDIRECT : REDIRECT;

        // 입장권이 죽었으면 로그인도 의미가 없다. 세션만 남겨 두면 다음에 다시 입장했을 때
        // 로그인 화면이 이미 로그인된 상태로 떠서 화면과 실제가 어긋난다.
        LoginSession.logout(request);

        // 만료된 쿠키를 그대로 두면 다음 요청에서도 똑같이 Redis를 한 번 더 친다.
        // 어차피 못 쓸 값이므로 여기서 지운다.
        response.addHeader("Set-Cookie", ExpiredCookies.pass());
        response.sendRedirect(redirect);
        return false;
    }
}
