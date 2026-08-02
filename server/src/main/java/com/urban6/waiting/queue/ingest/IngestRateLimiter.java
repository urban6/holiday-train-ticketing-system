package com.urban6.waiting.queue.ingest;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Redis에 넣는 속도에 상한을 건다. Kafka를 둔 이유가 바로 이것이다 — {@code enqueue.lua}와
 * {@code status.lua}가 Redis 단일 스레드를 공유하므로, 진입을 그냥 다 흘리면 개시 순간의
 * 스파이크가 이미 줄 서 있는 사람들의 순번 조회 지연으로 번진다. 진입 몫을 잘라 두면 그만큼
 * 폴링에 남는다.
 *
 * <p>초당 N건을 <b>균등한 간격으로</b> 통과시킨다. 창 단위 카운터로 세면 매 초 앞머리에 몰려
 * 창 경계에서 순간 2N이 된다. 유휴 뒤에도 몰아치지 않는다 — 다음 허용 시각을 현재로 끌어올린다.
 *
 * <p>대기 중에는 컨슈머 스레드가 poll을 하지 않는다. {@code ingest-per-second}를 아주 낮게
 * 내릴 때는 {@code max.poll.interval.ms}(기본 5분)와 {@code max-poll-records}를 함께 본다.
 */
final class IngestRateLimiter {

    private final long nanosPerPermit;
    private final AtomicLong nextPermitAt;

    IngestRateLimiter(int permitsPerSecond) {
        this.nanosPerPermit = 1_000_000_000L / permitsPerSecond;
        this.nextPermitAt = new AtomicLong(System.nanoTime());
    }

    /**
     * 자기 차례가 올 때까지 호출한 스레드를 재운다. 락을 잡지 않는다 — 컨슈머가 여러 개면
     * 이 한 점을 전부 통과하므로, 락을 두면 상한 장치가 그 자체로 병목이 된다.
     */
    void acquire() {
        long now = System.nanoTime();
        long previous;
        long slot;
        do {
            previous = nextPermitAt.get();
            slot = Math.max(previous, now);
        } while (!nextPermitAt.compareAndSet(previous, slot + nanosPerPermit));

        long wait = slot - now;
        if (wait > 0) {
            LockSupport.parkNanos(wait);
        }
    }
}
