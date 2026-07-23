package com.urban6.waiting.presentation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HomeController {

    /**
     * 시연용 시드 패널을 띄울 수 있는지. DemoProperties 빈을 주입하지 않고 값만 읽는 이유는
     * 기능이 꺼지면 그 빈 자체가 없기 때문이다 — 랜딩이 시연 기능의 존재 여부에 묶이면 안 된다.
     */
    private final boolean demoEnabled;

    public HomeController(@Value("${queue.demo.enabled:false}") boolean demoEnabled) {
        this.demoEnabled = demoEnabled;
    }

    /**
     * 대기열도 로그인도 필요 없는 유일한 화면이다.
     * 인터셉터가 걸리지 않으므로 부하 측정 시 진입 요청의 출발점이기도 하다.
     *
     * @param reason 게이트에 걸려 돌아왔을 때 안내 문구를 띄우기 위한 표시.
     *               직접 들어온 경우에는 없다.
     * @param demo   시연용 시드 패널을 여는 표시. 값은 보지 않고 파라미터의 유무만 본다 —
     *               {@code /?demo}처럼 값 없이 붙이는 게 가장 짧다.
     */
    @GetMapping("/")
    public String home(@RequestParam(required = false) String reason,
                       @RequestParam(required = false) String demo,
                       Model model) {
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

        // 설정이 꺼져 있으면 API가 없으므로 패널도 띄우지 않는다.
        // 파라미터만으로 열리게 두면 눌러도 아무 일이 없는 버튼이 생긴다.
        if (demo != null && demoEnabled) {
            model.addAttribute("demo", true);
        }
        return "index";
    }
}
