package com.urban6.waiting.presentation;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HomeController {

    /**
     * 대기열도 로그인도 필요 없는 유일한 화면이다.
     * 인터셉터가 걸리지 않으므로 부하 측정 시 진입 요청의 출발점이기도 하다.
     *
     * @param reason 게이트에 걸려 돌아왔을 때 안내 문구를 띄우기 위한 표시.
     *               직접 들어온 경우에는 없다.
     */
    @GetMapping("/")
    public String home(@RequestParam(required = false) String reason, Model model) {
        // 모르는 값이 오면(주소창에 직접 쳤거나 오래된 링크) 아무 문구도 띄우지 않는다.
        // 사용자가 넣은 문자열을 그대로 화면에 되돌려 주는 경로를 만들지 않기 위해서다.
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
