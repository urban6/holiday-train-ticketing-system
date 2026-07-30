package com.urban6.waiting.queue;

import com.urban6.waiting.queue.WaitingQueueService.Status;
import com.urban6.waiting.queue.WaitingQueueService.Ticket;
import com.urban6.waiting.queue.ingest.EnqueueSink;
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

    /**
     * Kafka 경유 진입은 아직 대기열에 등록되지 않았으므로 201이 아니라 202다.
     * 플래그를 여기까지 끌고 오지 않는다 — {@link EnqueueSink#SEQ_PENDING}만 보면 된다.
     */
    @PostMapping
    public ResponseEntity<Ticket> enqueue() {
        Ticket ticket = waitingQueueService.enqueue();

        HttpStatus status = ticket.seq() == EnqueueSink.SEQ_PENDING
                ? HttpStatus.ACCEPTED
                : HttpStatus.CREATED;

        return ResponseEntity.status(status).body(ticket);
    }

    @GetMapping("/{token}")
    public Status status(@PathVariable String token, @RequestParam String windowId) {
        return waitingQueueService.status(windowId, token);
    }

    /**
     * 대기열 이탈. 팝업을 닫거나 페이지를 떠날 때 클라이언트가 부른다.
     *
     * <p><b>DELETE가 아니라 POST인 이유는 {@code navigator.sendBeacon}이 POST만 보내기
     * 때문이다.</b> 탭을 닫는 순간에는 일반 fetch가 페이지와 함께 취소되므로 beacon이 유일한 수단이다.
     *
     * <p>언제나 204다 — 이탈 신호는 늦거나 중복으로 오는 것이 정상이고, 실패를 알려 봐야
     * 화면은 이미 사라진 뒤다. 아직 입장권이 없는 대기자가 부르는 API라 게이트도 타지 않는다.
     */
    @PostMapping("/{token}/leave")
    public ResponseEntity<Void> leave(@PathVariable String token, @RequestParam String windowId) {
        waitingQueueService.leave(windowId, token);

        return ResponseEntity.noContent().build();
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
                // 입장 자격이라 XSS로 새면 그대로 자리를 빼앗긴다.
                .httpOnly(true)
                .sameSite("Lax")
                .path("/")
                .maxAge(properties.sessionTtl())
                // HTTPS로 올리는 순간 secure(true)를 켜야 한다. 로컬 http에서는 켜면 저장되지 않는다.
                .build();
    }
}
