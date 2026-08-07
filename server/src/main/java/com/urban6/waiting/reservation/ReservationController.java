package com.urban6.waiting.reservation;

import com.urban6.waiting.auth.AdmissionGuard;
import com.urban6.waiting.auth.LoginSession;
import com.urban6.waiting.member.Member;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 열차 조회와 예약. 대기열 → 입장 → 로그인을 모두 통과한 사용자만 도달한다.
 *
 * <p>메서드 안에 입장권·로그인 검사가 없는 것은 빠뜨려서가 아니라 {@code WebConfig}의 게이트가
 * 앞단에 있기 때문이다. <b>새 경로를 추가하면 반드시 그 화이트리스트에 등록해야 한다.</b>
 */
@Controller
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final Clock clock;

    /**
     * 예약 화면. 조회 파라미터가 모두 있으면 검색 결과까지, 없으면 빈 폼과 내 예약만 그린다.
     *
     * <p>남은 시간은 만료 <b>시각</b>이 아니라 남은 <b>길이</b>로 내려보낸다 — 절대 시각을 주면
     * 클라이언트 시계가 틀어진 만큼 카운트다운이 통째로 어긋난다.
     */
    @GetMapping("/reservation")
    public String reservation(
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
            @RequestParam(required = false) SeatClass seatClass,
            @RequestParam(defaultValue = "1") int passengers,
            @RequestParam(required = false) String view,
            HttpServletRequest request, Model model) {

        Member member = LoginSession.current(request).orElseThrow();
        // 취소 후 리다이렉트가 view=history로 예약내역 탭을 다시 연다.
        model.addAttribute("historyTab", "history".equals(view));

        // AdmissionGuard가 통과시키며 남긴 값이다.
        long expiresAt = (long) request.getAttribute(AdmissionGuard.EXPIRES_AT);
        model.addAttribute("remainingMillis", Math.max(0, expiresAt - clock.millis()));

        model.addAttribute("reservations", reservationService.myReservations(member.id()));

        // 폼 제출값을 그대로 되돌려 놓아, 조회 후에도 입력이 남고 예약 버튼이 같은 조건을 실어 나른다.
        model.addAttribute("origin", origin);
        model.addAttribute("destination", destination);
        model.addAttribute("date", date);
        model.addAttribute("time", time);
        model.addAttribute("seatClass", seatClass);
        model.addAttribute("passengers", passengers);

        if (origin != null && destination != null && date != null && time != null && seatClass != null) {
            model.addAttribute("searchResults",
                    reservationService.search(origin, destination, date, time, seatClass));
        }

        return "reservation";
    }

    /**
     * 예약 확정. 성공·실패를 플래시로 남기고 조회 화면으로 되돌린다(PRG).
     *
     * <p>리다이렉트에 원래 조회 조건을 다시 실어, 새로고침 재제출을 막으면서 예약 직후 갱신된
     * 잔여석으로 같은 목록을 다시 조회하게 한다. 예약이 슬롯을 반납하지는 않는다 —
     * 남은 시간 동안 최대 한도까지 계속 예약할 수 있어야 하기 때문이다.
     */
    @PostMapping("/reservations")
    public String reserve(
            @RequestParam long trainId,
            @RequestParam SeatClass seatClass,
            @RequestParam int passengers,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
            HttpServletRequest request, RedirectAttributes redirect) {

        Member member = LoginSession.current(request).orElseThrow();
        try {
            reservationService.reserve(member.id(), trainId, seatClass, passengers);
            redirect.addFlashAttribute("reserveMessage", "예약이 완료되었습니다.");
        } catch (ReservationException e) {
            redirect.addFlashAttribute("reserveError", e.getMessage());
        }

        // date/time을 객체 그대로 넣으면 RedirectView가 로케일 포맷으로 직렬화해
        // (예: "26. 9. 24.") 되돌아오는 GET에서 ISO 파싱이 깨진다. ISO 문자열로 실어 보낸다.
        redirect.addAttribute("origin", origin);
        redirect.addAttribute("destination", destination);
        redirect.addAttribute("date", date != null ? date.toString() : null);
        redirect.addAttribute("time", time != null ? time.toString() : null);
        redirect.addAttribute("seatClass", seatClass);
        redirect.addAttribute("passengers", passengers);
        return "redirect:/reservation";
    }

    /**
     * 예약 취소. 성공·실패를 플래시로 남기고 예약내역 탭으로 되돌린다(PRG).
     *
     * <p>취소는 상태 변경이라 POST다. 조회 조건을 싣지 않으므로 조회 결과는 비고, {@code view=history}로
     * 예약내역 탭이 열린 채 갱신된 목록을 다시 본다.
     */
    @PostMapping("/reservations/{id}/cancel")
    public String cancel(@PathVariable long id,
                         HttpServletRequest request, RedirectAttributes redirect) {

        Member member = LoginSession.current(request).orElseThrow();
        try {
            reservationService.cancel(member.id(), id);
            redirect.addFlashAttribute("reserveMessage", "예약이 취소되었습니다.");
        } catch (ReservationException e) {
            redirect.addFlashAttribute("reserveError", e.getMessage());
        }

        redirect.addAttribute("view", "history");
        return "redirect:/reservation";
    }
}
