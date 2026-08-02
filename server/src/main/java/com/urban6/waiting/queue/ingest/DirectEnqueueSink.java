package com.urban6.waiting.queue.ingest;

import com.urban6.waiting.queue.DailyWindow.Window;
import com.urban6.waiting.queue.QueueProperties;
import com.urban6.waiting.queue.WaitingQueueRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@code enqueue.lua}를 요청 스레드에서 바로 부른다. Kafka 도입 전과 같은 경로다.
 *
 * <p>플래그를 켠 상태에서도 이 빈은 그대로 뜬다 — 컨슈머가 결국 이것을 부른다. 두 경로가 다른
 * 것은 그 호출이 요청 스레드에서 일어나는지 컨슈머에서 일어나는지 하나뿐이다.
 */
@Component
@RequiredArgsConstructor
class DirectEnqueueSink implements EnqueueSink {

    private final WaitingQueueRepository repository;
    private final QueueProperties properties;

    @Override
    public Accepted submit(Window window, Instant now) {
        String token = QueueTokens.issue();
        return new Accepted(token, register(window, token, now));
    }

    /**
     * 이미 발급된 토큰을 대기열에 등록한다. {@link EnqueueConsumer}가 부른다 — 여기서 토큰을 새로
     * 만들면 사용자가 들고 있는 것과 어긋나 자기 순번을 영원히 찾지 못한다.
     *
     * <p><b>기한은 인자로 받지 않고 여기서 지금 시각으로 다시 계산한다.</b> 접수 시각으로 계산한
     * 기한을 넘겨받으면 컨슈머가 밀린 만큼 이미 지나 있어, 넣자마자 스위퍼가 걷어낸다.
     */
    long register(Window window, String token, Instant now) {
        // 이 시점에는 아직 순번을 모르므로 첫 조회 기한은 하한으로 잡는다.
        long firstPollDeadline = now.toEpochMilli()
                + properties.minPollInterval().toMillis()
                + properties.pollGrace().toMillis();

        return repository.enqueue(window.windowId(), token, firstPollDeadline,
                window.waitingDeadline(), window.seqDeadline());
    }

    /** 등록이 곧 응답이라 "접수됐지만 아직 등록 전"인 구간이 없다. */
    @Override
    public boolean maybePending(String token, Instant now) {
        return false;
    }
}
