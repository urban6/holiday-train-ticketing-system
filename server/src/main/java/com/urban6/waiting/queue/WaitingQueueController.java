package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueService.Status;
import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/waiting-queue")
@RequiredArgsConstructor
public class WaitingQueueController {

    /** 값은 "{date}.{token}"이다. */
    public static final String PASS_COOKIE = "pass";

    private final WaitingQueueService waitingQueueService;
    private final QueueProperties properties;

    @PostMapping
    public ResponseEntity<Ticket> enqueue() {
        Ticket ticket = waitingQueueService.enqueue();

        return ResponseEntity.status(HttpStatus.CREATED).body(ticket);
    }

    @GetMapping("/{token}")
    public Status status(@PathVariable String token, @RequestParam String date) {
        return waitingQueueService.status(date, token);
    }

    /** DELETE가 아니라 POST인 이유는 탭을 닫을 때 쓰는 navigator.sendBeacon이 POST만 보내기 때문이다. */
    @PostMapping("/{token}/leave")
    public ResponseEntity<Void> leave(@PathVariable String token, @RequestParam String date) {
        waitingQueueService.leave(date, token);

        return ResponseEntity.noContent().build();
    }

    /** 대기 토큰은 JS 메모리에만 있으므로, 여기서 쿠키로 바꿔 심어야 로그인·예약 화면까지 따라간다. */
    @PostMapping("/{token}/admission")
    public ResponseEntity<Void> claim(@PathVariable String token, @RequestParam String date) {
        waitingQueueService.claim(date, token);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, passCookie(date, token).toString())
                .build();
    }

    private ResponseCookie passCookie(String date, String token) {
        return ResponseCookie.from(PASS_COOKIE, date + "." + token)
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.sessionTtl())
                // HTTPS로 올리면 secure(true)를 켠다. 로컬 http에서 켜면 저장되지 않는다.
                .build();
    }
}
