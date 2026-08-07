package com.urban6.waiting.queue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 해시 태그에 걸린 두 불변식을 확인한다. 실제 클러스터 없이(Spring 컨텍스트도 Redis도 필요
 * 없다) 확인할 수 있는 것은 슬롯이 {@code CRC16(태그) mod 16384}라는 순수 함수이기 때문이다.
 *
 * <ol>
 *   <li><b>한 샤드의 네 키는 같은 슬롯</b> — Redis Cluster는 {@code {...}} 안쪽만 해싱하므로
 *       접두사(waiting/active/poll)가 달라도 이 부분만 같으면 네 키가 같은 노드에 떨어져
 *       promote·status·leave·sweep 스크립트가 CROSSSLOT 없이 원자적으로 돈다.
 *   <li><b>샤드끼리는 다른 마스터</b> — 태그가 셋뿐이라 해싱이 고르게 흩어 주지 않는다.
 *       둘이 같은 구간에 몰리면 마스터 하나가 대기열 트래픽을 아예 못 받는데, 그건 어디서도
 *       실패하지 않고 처리량으로만 조용히 드러난다.
 * </ol>
 *
 * <p>두 번째가 지금까지 {@code docs/redis-cluster-bootstrap.md}의 손 절차
 * ({@code CLUSTER KEYSLOT} 3회)로만 남아 있던 것이다. 여기 옮겨 두면 {@code SHARD_TAGS}를
 * 고치는 순간 바로 걸린다.
 */
class QueueKeysTest {

    private static final String DATE = "20260807";

    /** QueueKeys.SHARD_TAGS와 같은 값. 이 프로젝트는 샤드 3개(마스터 3대)를 전제한다. */
    private static final String[] EXPECTED_TAGS = {"a", "b", "c"};

    @Test
    @DisplayName("한 샤드의 네 키는 같은 해시 태그를 공유한다")
    void allFourKeysOfOneShardShareTheSameHashTag() {
        for (int shard = 0; shard < EXPECTED_TAGS.length; shard++) {
            String tag = "{%s}".formatted(EXPECTED_TAGS[shard]);

            assertThat(QueueKeys.waiting(DATE, shard)).contains(tag);
            assertThat(QueueKeys.seq(DATE, shard)).contains(tag);
            assertThat(QueueKeys.active(DATE, shard)).contains(tag);
            assertThat(QueueKeys.pollDeadline(DATE, shard)).contains(tag);
        }
    }

    @Test
    @DisplayName("샤드가 다르면 해시 태그도 다르다")
    void differentShardsGetDifferentHashTags() {
        assertThat(QueueKeys.waiting(DATE, 0)).doesNotContain("{b}");
        assertThat(QueueKeys.waiting(DATE, 1)).doesNotContain("{a}");
    }

    @Test
    @DisplayName("샤드 태그가 넘치면(SHARD_TAGS 밖) 조용히 틀린 키를 만들지 않고 던진다")
    void unmappedShardFailsLoudly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> QueueKeys.waiting(DATE, 99))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("샤드별 슬롯이 부트스트랩 문서의 CLUSTER KEYSLOT 값과 같다")
    void eachShardLandsOnItsDocumentedSlot() {
        // CRC16은 바뀌지 않으므로 이 세 수는 영구적 참이다. docs/redis-cluster-bootstrap.md
        // 2단계가 실제 클러스터에 물어서 얻는 값과 같아야 한다 — 다르면 둘 중 하나가 낡은 것이다.
        assertThat(slotOf(QueueKeys.waiting(DATE, 0))).isEqualTo(15495);
        assertThat(slotOf(QueueKeys.waiting(DATE, 1))).isEqualTo(3300);
        assertThat(slotOf(QueueKeys.waiting(DATE, 2))).isEqualTo(7365);
    }

    @Test
    @DisplayName("세 샤드의 대기열이 서로 다른 마스터에 떨어진다 — 겹치면 마스터 하나가 논다")
    void eachShardLandsOnADifferentMaster() {
        // 이 단언이 잡으려는 것은 통계적 흔들림이 아니라 태그 선택의 실수다. 샤드 번호를 그대로
        // 태그로 쓰면({0}=13907, {1}=9842, {2}=5649) 1과 2가 가운데 구간에 겹치는데, 그 상태로도
        // 앱은 멀쩡히 돌고 CROSSSLOT도 안 난다. 드러나는 곳은 처리량뿐이다.
        int[] master = new int[EXPECTED_TAGS.length];
        for (int shard = 0; shard < EXPECTED_TAGS.length; shard++) {
            master[shard] = masterOf(slotOf(QueueKeys.waiting(DATE, shard)));
        }

        for (int a = 0; a < master.length; a++) {
            for (int b = a + 1; b < master.length; b++) {
                assertThat(master[a])
                        .as("샤드 %d(슬롯 %d)와 샤드 %d(슬롯 %d)가 같은 마스터 %d에 있다",
                                a, slotOf(QueueKeys.waiting(DATE, a)),
                                b, slotOf(QueueKeys.waiting(DATE, b)), master[a])
                        .isNotEqualTo(master[b]);
            }
        }
    }

    /**
     * {@code redis-cli --cluster create}가 마스터 3대에 나눠 주는 슬롯 구간의 끝. 이 값은
     * 클러스터를 만든 직후의 배정이라, {@code --cluster reshard}나 노드 추가로 구간이 움직이면
     * 여기 적힌 것과 달라진다. 샤드 수를 바꾸면 이 표도 다시 구해야 한다.
     */
    private static final int[] MASTER_RANGE_END = {5460, 10922, 16383};

    private static int masterOf(int slot) {
        for (int master = 0; master < MASTER_RANGE_END.length; master++) {
            if (slot <= MASTER_RANGE_END[master]) {
                return master;
            }
        }
        throw new IllegalStateException("슬롯이 16383을 넘었습니다: " + slot);
    }

    /**
     * Redis가 키의 슬롯을 정하는 방법 그대로다. 중괄호가 있고 그 사이가 비어 있지 않으면
     * 안쪽만, 아니면 키 전체를 해싱한다. 이 규칙을 같이 구현해야 태그를 빠뜨린 키가 통과하지
     * 않는다 — 태그 없는 {@code waiting:20260807:a}는 접두사까지 해싱돼 형제 키들과
     * 다른 슬롯으로 흩어진다.
     */
    private static int slotOf(String key) {
        int open = key.indexOf('{');
        if (open >= 0) {
            int close = key.indexOf('}', open + 1);
            if (close > open + 1) {
                return crc16(key.substring(open + 1, close)) % 16384;
            }
        }
        return crc16(key) % 16384;
    }

    /** CRC16-CCITT(XMODEM) — 다항식 0x1021, 초기값 0. Redis Cluster가 쓰는 그 함수다. */
    private static int crc16(String s) {
        int crc = 0;
        for (byte b : s.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }
}
