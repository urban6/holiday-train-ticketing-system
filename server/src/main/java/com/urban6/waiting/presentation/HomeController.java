package com.urban6.waiting.presentation;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HomeController {

    /** @param reason 게이트에 걸려 돌아왔을 때 띄울 안내 문구의 종류 */
    @GetMapping("/")
    public String home(@RequestParam(required = false) String reason, Model model) {
        // 사용자가 넣은 문자열을 화면에 되돌려 주지 않도록 아는 값만 문구로 바꾼다.
        String notice = switch (reason == null ? "" : reason) {
            case "admission-required" ->
                    "입장 가능 시간이 지났거나 대기열을 거치지 않았습니다. 다시 신청해 주세요.";
            case "reservation-expired" ->
                    "예약 가능 시간이 지났습니다. 다시 신청해 주세요.";
            default -> null;
        };

        if (notice != null) {
            model.addAttribute("notice", notice);
        }

        return "index";
    }
}
