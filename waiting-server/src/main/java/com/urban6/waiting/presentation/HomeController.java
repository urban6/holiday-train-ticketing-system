package com.urban6.waiting.presentation;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "index";
    }

    /**
     * 입장한 사용자가 도착하는 곳. 아직 자리표시자다 — 인증도, pass 쿠키 검증도 없다.
     * 이 페이즈에서 이 화면이 하는 일은 "대기 → 입장 → 페이지 이동"이 실제로 이어지는지
     * 눈으로 확인시켜 주는 것뿐이다.
     */
    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
