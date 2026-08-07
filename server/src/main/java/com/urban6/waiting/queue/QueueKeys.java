package com.urban6.waiting.queue;

import java.util.regex.Pattern;

/**
 * Redis 키를 조립하는 유일한 곳. 키 리터럴이 다른 곳에 새어 나가면 안 된다.
 *
 * <pre>
 * waiting:{date}:{shard}       ZSet   member=uuid, score=seq
 * waiting:{date}:{shard}:seq   String 샤드별 단조 증가 순번 카운터
 * active:{date}:{shard}        ZSet   member=uuid, score=만료 epoch ms
 * poll:{date}:{shard}          ZSet   member=uuid, score=다음 폴링 기한 epoch ms
 * </pre>
 *
 * <p><b>{@code date}를 키에서 빼면 안 된다.</b> 키 하나하나는 판매 창 하나에 딸린 것이고,
 * 날짜가 그 창의 이름이다. TTL만 믿고 날짜를 지우면 유예가 자정을 넘는 순간 어제 데이터와
 * 섞인다 — 지금 설정({@code open 00:00} / {@code close 23:59:59})에서 유예는 waiting 10분,
 * active 1시간, seq 12시간이라 셋 다 다음 창 안으로 들어온다. 그러면 어제 대기자가 오늘 줄
 * 앞에 서고, 더 나쁘게는 {@code enqueue.lua}의 TTL 스탬프 조건({@code seq == 1 or PTTL < 0})이
 * 둘 다 거짓이 되어 오늘 대기열이 어제 만료 시각에 통째로 사라진다. 키 길이를 줄여서 얻는
 * 것은 없다 — 전체 키가 샤드 수 × 4개뿐이라 메모리에 잡히지도 않는다.
 *
 * <p><b>한 샤드의 네 키는 반드시 같은 Redis 노드에 있어야 한다.</b> promote는 waiting에서
 * 꺼내 active에 넣는 것을 한 스크립트로 묶어 정원을 지키는데, 두 키가 다른 노드면 그
 * 원자성이 사라진다.
 *
 * <p>키의 {@code shard} 부분이 {@code {a}}처럼 중괄호로 감싸인 해시 태그다. Redis Cluster는
 * 키 전체가 아니라 중괄호 안쪽만 해싱해 슬롯을 정하므로, 접두사(waiting/active/poll)가 달라도
 * 같은 샤드의 네 키는 항상 같은 슬롯 — 곧 같은 노드에 떨어진다. status·promote·leave·sweep
 * 스크립트가 두 키 이상을 함께 넘기는데도 CROSSSLOT을 만나지 않는 이유가 이것이다. 어느 노드가
 * 그 슬롯을 실제로 갖는지는 Cluster가 알아서 라우팅한다 — 이 클래스는 해시 태그를 정하기만
 * 하고, 물리 배치는 신경 쓰지 않는다.
 *
 * <p>태그 문자는 샤드 번호 그대로({@code "0"}, {@code "1"}, {@code "2"}) 쓰지 않는다.
 * 로컬 3-마스터 클러스터로 직접 확인해 보니 {@code CLUSTER KEYSLOT "{0}"}=13907,
 * {@code "{1}"}=9842, {@code "{2}"}=5649로 나왔는데, 16384슬롯을 3등분한 구간
 * (0~5460 / 5461~10922 / 10923~16383)에 넣으면 1과 2가 같은 구간에 몰려 마스터 하나가
 * 아예 트래픽을 못 받는다. {@link #SHARD_TAGS}는 같은 방법으로 세 구간에 하나씩 떨어지는
 * 것을 확인한 문자다 — 부트스트랩 절차는 docs/redis-cluster-bootstrap.md 참고.
 */
public final class QueueKeys {

    private QueueKeys() {}

    /**
     * 샤드 번호 → 해시 태그 문자. {@code CLUSTER KEYSLOT}으로 세 구간에 하나씩 떨어지는 것을
     * 확인한 값이라 순서를 바꾸거나 원소를 빼면 안 된다. 샤드 수를 늘리려면 새 문자를 추가하고
     * 같은 방법으로 다시 확인해야 한다.
     */
    private static final String[] SHARD_TAGS = {"a", "b", "c"};

    /**
     * 태그가 마련된 샤드 수. {@link QueueProperties}가 기동 검증에 쓴다 — 이 수를 넘는
     * {@code queue.shard-count}는 설정으로는 멀쩡해 보이다가 그 샤드로 가는 첫 요청에서야
     * {@link #tag}가 던진다.
     */
    public static int maxShardCount() {
        return SHARD_TAGS.length;
    }

    /** DailyWindow가 만드는 BASIC_ISO_DATE 형식(yyyyMMdd). */
    private static final Pattern DATE = Pattern.compile("\\d{8}");

    /**
     * 판매 창의 날짜인지 검사한다 — 아무 날짜가 아니라 {@link DailyWindow}가 만든 그 창의
     * 이름이고, 그대로 Redis 키가 되는 값이다.
     *
     * <p>예외를 던지지 않는다. pass 쿠키 검증은 형식이 깨졌을 때 400이 아니라 리다이렉트로
     * 돌려보내야 한다 — 우리가 심은 값이 깨졌다는 건 "입장 자격이 없다"에 가깝다.
     */
    public static boolean isValidDate(String date) {
        return date != null && DATE.matcher(date).matches();
    }

    /** 클라이언트가 보낸 date는 그대로 Redis 키가 되므로 쓰기 전에 검사한다. */
    public static String requireValidDate(String date) {
        if (!isValidDate(date)) {
            throw new QueueException.InvalidRequest("date 형식이 올바르지 않습니다.");
        }
        return date;
    }

    /**
     * 이 토큰이 속한 샤드. 토큰 하나로 정해지므로 조회·이탈·입장 확정이 언제나 같은 샤드로
     * 돌아온다 — 어느 샤드로 넣었는지 따로 기억해 둘 필요가 없다.
     *
     * <p>해시가 고르게 흩어지는 것이 순번 근사의 전제다({@link WaitingQueueService} 참고).
     *
     * <p>{@code hashCode()}는 음수가 될 수 있어 {@code %}가 아니라 {@code floorMod}다.
     * {@code %}로 두면 음수 샤드 번호가 나와 목록 인덱싱에서 터진다.
     */
    public static int shardOf(String token, int shardCount) {
        return Math.floorMod(token.hashCode(), shardCount);
    }

    /** 샤드 번호에 대응하는 해시 태그 문자. {@link #SHARD_TAGS} 참고. */
    private static String tag(int shard) {
        if (shard < 0 || shard >= SHARD_TAGS.length) {
            throw new IllegalStateException(
                    "샤드 %d에 쓸 해시 태그가 없습니다. SHARD_TAGS를 늘리고 CLUSTER KEYSLOT으로 다시 확인하세요."
                            .formatted(shard));
        }
        return SHARD_TAGS[shard];
    }

    public static String waiting(String date, int shard) {
        return "waiting:%s:{%s}".formatted(date, tag(shard));
    }

    public static String seq(String date, int shard) {
        return "waiting:%s:{%s}:seq".formatted(date, tag(shard));
    }

    /** 입장한 사용자. member=uuid, score=만료 epoch ms. */
    public static String active(String date, int shard) {
        return "active:%s:{%s}".formatted(date, tag(shard));
    }

    /**
     * 대기자가 다음 조회를 보내야 하는 기한. member=uuid, score=epoch ms.
     *
     * <p>score가 과거가 아니라 미래를 가리킨다. 판정 기준이 사람마다 다른데 "마지막으로 본 시각"을
     * 저장하면 스위퍼의 ZRANGEBYSCORE가 사람별 임계값을 알 방법이 없다(ZSet은 join이 안 된다).
     * 주기를 미리 더해 구워 두면 스위퍼의 임계값이 지금 시각 하나로 끝난다.
     *
     * <p>waiting과 따로 두는 이유는 waiting의 score가 이미 seq라 기한으로 바꿀 수 없기 때문이다.
     */
    public static String pollDeadline(String date, int shard) {
        return "poll:%s:{%s}".formatted(date, tag(shard));
    }
}
