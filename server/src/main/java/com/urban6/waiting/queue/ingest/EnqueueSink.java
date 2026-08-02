package com.urban6.waiting.queue.ingest;

import com.urban6.waiting.queue.DailyWindow.Window;
import java.time.Instant;

/**
 * 대기열에 넣는 한 걸음. 진입 경로의 유일한 갈림길이다.
 *
 * <p>{@code queue.enqueue-via-kafka}가 어느 구현이 뜰지 정한다. 분기를 여기 하나로 모은 이유는
 * 플래그를 끈 상태가 도입 전과 똑같아야 전후 비교가 성립하기 때문이다 — 같은 jar에서 플래그만
 * 바꿔 두 번 재려고 만든 이음매다. 토큰 발급도 여기 있다({@link QueueTokens}).
 */
public interface EnqueueSink {

    /** 아직 순번이 발급되지 않았다. Kafka 경유에서만 나온다. */
    long SEQ_PENDING = -1;

    /**
     * 발급된 토큰과 순번. {@code seq}는 클라이언트가 쓰지 않지만 정합성 검사에 남긴다 —
     * 보낸 요청 수와 마지막 seq가 맞는지가 "순번 유실도 중복도 없다"의 근거다.
     */
    record Accepted(String token, long seq) {}

    Accepted submit(Window window, Instant now);

    /**
     * 이 토큰이 "접수는 됐지만 아직 대기열 등록 전"일 수 있는가.
     * 어느 ZSet에도 없는 토큰을 만료로 볼지 기다리게 할지 정한다. 직접 진입은 언제나 false다.
     */
    boolean maybePending(String token, Instant now);
}
