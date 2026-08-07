package com.urban6.waiting.auth;

import com.urban6.waiting.auth.PassCookie.Pass;
import com.urban6.waiting.member.Member;
import com.urban6.waiting.member.MemberException;
import com.urban6.waiting.member.MemberService;
import com.urban6.waiting.queue.QueueException;
import com.urban6.waiting.queue.WaitingQueueService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 대기열을 통과한 사용자의 로그인. 입장권 검사가 메서드 안에 없는 것은 빠뜨려서가 아니라
 * {@link WebConfig}의 게이트가 앞단에 있기 때문이다.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;
    private final WaitingQueueService waitingQueueService;

    @GetMapping("/login")
    public String loginForm() {
        return "login";
    }

    /**
     * 실패해도 200으로 같은 화면을 다시 그린다. 아이디는 남기고 비밀번호는 비운다 —
     * 비밀번호를 HTML에 되돌려 놓으면 브라우저 캐시·기록에 남는다.
     *
     * <p>성공하면 예약 시간을 연다. 이 시점부터 queue.reservation-ttl 동안만 머무를 수 있다.
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
            waitingQueueService.startReservation(pass.date(), pass.token());
        } catch (QueueException.Expired e) {
            // 세션을 만들기 전에 걸러야 "로그인은 됐는데 입장권은 없는" 상태가 생기지 않는다.
            log.debug("예약 시간을 열지 못했다. 입장권이 이미 만료됐다.");
            return "redirect:" + AdmissionGuard.EXPIRED_REDIRECT;
        }

        LoginSession.login(request, member);
        return "redirect:/reservation";
    }

    /**
     * 게이트를 걸지 않는다. 세션을 정리하러 온 사람을 자격이 없다고 돌려보내면 세션만 남는다.
     * 세션과 함께 활성 슬롯도 반납해 뒷사람이 그만큼 빨리 들어온다.
     */
    @PostMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        Optional<Pass> pass = PassCookie.read(request);
        if (pass.isPresent()) {
            try {
                waitingQueueService.release(pass.get().date(), pass.get().token());
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
