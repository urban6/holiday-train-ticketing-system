package com.urban6.waiting.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 시연용 대기열 시드 설정. 값은 application.yml이 단일 출처다.
 *
 * <p>{@code enabled}가 false면 {@link DemoConfig}의 조건이 걸려 이 record 자체가
 * 바인딩되지 않는다. 즉 여기까지 왔다는 건 기능이 켜져 있다는 뜻이다.
 *
 * @param enabled      기능 스위치. 켜져 있으면 누구나 대기열을 부풀릴 수 있으므로
 *                     외부에 노출되는 환경에서는 반드시 false여야 한다.
 * @param maxCount     한 번에 시드할 수 있는 최대 인원. 실수로 0을 하나 더 붙였을 때
 *                     Redis가 통째로 멈추는 걸 막는 상한이다.
 * @param maxAttrition 봇이 대기 중에 포기하고 나가는 최대 인원(1초당). 매 주기 0~이 값에서
 *                     무작위로 정해져 순번이 줄어드는 폭을 흔든다.
 *                     0이면 이탈이 꺼지고 정확히 max-batch씩 줄어든다 — 되돌리는 스위치다.
 */
@ConfigurationProperties("queue.demo")
public record DemoProperties(boolean enabled, int maxCount, int maxAttrition) {

    public DemoProperties {
        if (maxCount <= 0) {
            throw new IllegalArgumentException("queue.demo.max-count는 1 이상이어야 합니다: " + maxCount);
        }
        if (maxAttrition < 0) {
            throw new IllegalArgumentException(
                    "queue.demo.max-attrition은 0 이상이어야 합니다: " + maxAttrition);
        }
    }
}
