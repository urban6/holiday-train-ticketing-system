package com.urban6.waiting.auth;

import com.urban6.waiting.auth.PassCookie.Pass;
import com.urban6.waiting.member.Member;
import com.urban6.waiting.member.MemberException;
import com.urban6.waiting.member.MemberService;
import com.urban6.waiting.queue.QueueException;
import com.urban6.waiting.queue.QueueProperties;
import com.urban6.waiting.queue.WaitingQueueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 대기열을 통과한 사용자의 로그인.
 *
 * <p>이 컨트롤러의 모든 화면은 {@link WebConfig}에 등록된 인터셉터를 거친다.
 * 여기 메서드 안에 입장권 검사가 없는 것은 빠뜨려서가 아니라 게이트가 앞단에 있기 때문이다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;
    private final WaitingQueueService waitingQueueService;
    private final QueueProperties queueProperties;
    private final Clock clock;

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }

    /**
     * <p>실패해도 상태 코드는 200이고 같은 화면을 다시 그린다. 리다이렉트로 돌려보내면
     * 입력값을 세션이나 플래시에 실어 날라야 하는데, 폼 하나 때문에 그럴 이유가 없다.
     *
     * <p>아이디는 남기고 비밀번호는 비운다. 다시 칠 때 아이디까지 치게 하는 건 번거롭기만 하고,
     * 비밀번호를 HTML에 되돌려 놓는 건 브라우저 캐시·기록에 남을 이유를 만든다.
     *
     * <p>인증에 성공하면 예약 시간을 연다. 이 시점부터 queue.reservation-ttl 동안만
     * 예약 화면에 머무를 수 있다.
     */
    @PostMapping("/login")
    public String login(@RequestParam String loginId,
                        @RequestParam String password,
                        HttpServletRequest request,
                        Model model) {
        Member member;
        try {
            member = memberService.authenticate(loginId, password);
        } catch (MemberException.InvalidCredentials e) {
            // 어떤 아이디로 실패했는지는 남기지 않는다. 로그가 곧 계정 목록이 된다.
            log.debug("로그인 실패");
            model.addAttribute("error", e.getMessage());
            model.addAttribute("loginId", loginId);
            return "login";
        }

        // AdmissionGuard를 통과했으므로 반드시 있다.
        Pass pass = PassCookie.read(request).orElseThrow();
        try {
            waitingQueueService.startReservation(pass.windowId(), pass.token());
        } catch (QueueException.Expired e) {
            // 게이트 검사와 여기 사이에 슬롯이 회수됐다. 세션을 만들기 전에 걸러야
            // "로그인은 됐는데 입장권은 없는" 상태가 생기지 않는다.
            log.debug("예약 시간을 열지 못했다. 입장권이 이미 만료됐다.");
            return "redirect:" + AdmissionGuard.EXPIRED_REDIRECT;
        }

        LoginSession.login(request, member);
        return "redirect:/reservation";
    }

    /**
     * 다음 페이즈(좌석·예약)의 자리표시자.
     * 지금 이 화면이 하는 일은 로그인이 실제로 걸렸는지 눈으로 확인시켜 주는 것뿐이다.
     *
     * <p>남은 시간은 만료 <b>시각</b>이 아니라 남은 <b>길이</b>로 내려보낸다.
     * 절대 시각을 주면 클라이언트 시계가 틀어진 만큼 카운트다운이 통째로 어긋난다.
     */
    @GetMapping("/reservation")
    public String reservation(HttpServletRequest request, Model model) {
        // LoginGuard를 통과했으므로 반드시 있다.
        model.addAttribute("member", LoginSession.current(request).orElseThrow());

        // AdmissionGuard가 통과시키며 남긴 값이다. 여기 오는 요청은 반드시 게이트를 거친다.
        long expiresAt = (long) request.getAttribute(AdmissionGuard.EXPIRES_AT);
        model.addAttribute("remainingMillis", Math.max(0, expiresAt - clock.millis()));

        // 안내 문구에 쓸 제한 시간. 화면에 숫자를 박아 두면 설정을 바꿀 때마다 화면이 거짓말을 한다.
        model.addAttribute("reservationSeconds", queueProperties.reservationTtl().toSeconds());

        return "reservation";
    }

    /**
     * 게이트를 걸지 않는다. 입장권이 만료된 상태에서도 로그아웃은 되어야 한다 —
     * 세션을 정리하러 온 사람을 자격이 없다고 돌려보내면 세션만 남는다.
     *
     * <p>세션과 함께 활성 슬롯도 반납한다. 만료를 기다리지 않으므로 뒷사람이 그만큼 빨리 들어오고,
     * 로그아웃 후 다시 로그인해서 예약 시간을 새로 받는 경로도 함께 막힌다.
     */
    @PostMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        Optional<Pass> pass = PassCookie.read(request);
        if (pass.isPresent()) {
            try {
                waitingQueueService.release(pass.get().windowId(), pass.get().token());
            } catch (QueueException e) {
                // Redis가 죽었다고 로그아웃까지 실패하면 안 된다. 슬롯은 만료로 회수된다.
                log.warn("입장권 반납 실패. 만료를 기다린다: {}", e.getMessage());
            }
        }

        LoginSession.logout(request);
        response.addHeader("Set-Cookie", ExpiredCookies.pass());
        return "redirect:/";
    }
}
