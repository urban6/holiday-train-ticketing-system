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
 * 입장권·로그인 검사는 {@code WebConfig}의 게이트가 앞단에서 한다.
 * <b>새 경로를 추가하면 반드시 그 화이트리스트에 등록해야 한다.</b>
 */
@Controller
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
    private final Clock clock;

    /** 남은 시간은 만료 시각이 아니라 길이로 내려보낸다. 클라이언트 시계가 틀려도 카운트다운이 맞는다. */
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
        model.addAttribute("historyTab", "history".equals(view));

        long expiresAt = (long) request.getAttribute(AdmissionGuard.EXPIRES_AT);
        model.addAttribute("remainingMillis", Math.max(0, expiresAt - clock.millis()));

        model.addAttribute("reservations", reservationService.myReservations(member.id()));

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

    /** 예약해도 슬롯은 반납하지 않는다. 남은 시간 동안 한도까지 계속 예약할 수 있다. */
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

        // 객체 그대로 넣으면 로케일 포맷("26. 9. 24.")으로 직렬화돼 ISO 파싱이 깨진다.
        redirect.addAttribute("origin", origin);
        redirect.addAttribute("destination", destination);
        redirect.addAttribute("date", date != null ? date.toString() : null);
        redirect.addAttribute("time", time != null ? time.toString() : null);
        redirect.addAttribute("seatClass", seatClass);
        redirect.addAttribute("passengers", passengers);
        return "redirect:/reservation";
    }

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
