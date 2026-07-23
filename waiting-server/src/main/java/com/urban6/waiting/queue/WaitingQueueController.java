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

    /** 값은 "{windowId}.{token}"이다. 활성 조회에 둘 다 필요한데 쿠키를 나눌 이유가 없다. */
    public static final String PASS_COOKIE = "pass";

    private final WaitingQueueService waitingQueueService;
    private final QueueProperties properties;

    @PostMapping
    public ResponseEntity<Ticket> enqueue() {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(waitingQueueService.enqueue());
    }

    @GetMapping("/{token}")
    public Status status(@PathVariable String token, @RequestParam String windowId) {
        return waitingQueueService.status(windowId, token);
    }

    /**
     * 입장 확정. 대기 토큰은 JS 메모리에만 살아서 페이지를 옮기는 순간 사라진다.
     * 여기서 쿠키로 바꿔 심어야 로그인·예약 화면까지 활성 상태가 따라간다.
     */
    @PostMapping("/{token}/admission")
    public ResponseEntity<Void> claim(@PathVariable String token, @RequestParam String windowId) {
        waitingQueueService.claim(windowId, token);   // 실패하면 Expired → 404

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, passCookie(windowId, token).toString())
                .build();
    }

    private ResponseCookie passCookie(String windowId, String token) {
        return ResponseCookie.from(PASS_COOKIE, windowId + "." + token)
                // JS가 읽을 일이 없다. 대기 토큰과 달리 이건 입장 자격이라 XSS로 새면 그대로 자리를 빼앗긴다.
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.sessionTtl())
                // secure(true)를 붙이면 로컬 http에서 쿠키가 아예 저장되지 않는다.
                // HTTPS로 올리는 순간 반드시 켜야 한다.
                .build();
    }
}
