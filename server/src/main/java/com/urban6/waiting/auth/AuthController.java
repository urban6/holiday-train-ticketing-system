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

/** 입장권 검사는 {@link WebConfig}의 게이트가 앞단에서 한다. */
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

    @PostMapping("/login")
    public String login(@RequestParam String loginId,
                        @RequestParam String password,
                        HttpServletRequest request,
                        Model model) {
        Member member;
        try {
            member = memberService.authenticate(loginId, password);
        } catch (MemberException.InvalidCredentials e) {
            // 실패한 아이디는 로그에 남기지 않는다. 로그가 곧 계정 목록이 된다.
            log.debug("로그인 실패");
            model.addAttribute("error", e.getMessage());
            model.addAttribute("loginId", loginId);
            return "login";
        }

        Pass pass = PassCookie.read(request).orElseThrow();
        try {
            waitingQueueService.startReservation(pass.date(), pass.token());
        } catch (QueueException.Expired e) {
            // 세션을 만들기 전에 걸러야 로그인만 되고 입장권은 없는 상태가 생기지 않는다.
            log.debug("예약 시간을 열지 못했다. 입장권이 이미 만료됐다.");
            return "redirect:" + AdmissionGuard.EXPIRED_REDIRECT;
        }

        LoginSession.login(request, member);
        return "redirect:/reservation";
    }

    /** 게이트를 걸지 않는다. 자격이 없다고 돌려보내면 세션만 남는다. */
    @PostMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        Optional<Pass> pass = PassCookie.read(request);
        if (pass.isPresent()) {
            try {
                waitingQueueService.release(pass.get().date(), pass.get().token());
            } catch (QueueException e) {
                // 슬롯은 만료로 회수되므로 Redis 장애가 로그아웃을 막지 않게 한다.
                log.warn("입장권 반납 실패. 만료를 기다린다: {}", e.getMessage());
            }
        }

        LoginSession.logout(request);
        response.addHeader("Set-Cookie", ExpiredCookies.pass());
        return "redirect:/";
    }
}
