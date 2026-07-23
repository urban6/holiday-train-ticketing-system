package com.urban6.waiting.member;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 회원 조회. 지금 필요한 쿼리는 하나뿐이다.
 *
 * <p>JPA 대신 {@link JdbcClient}인 이유는 이 클래스가 전부다 — 테이블 하나, 쿼리 하나에
 * 엔티티와 영속성 컨텍스트를 얹을 이유가 없다. 실행되는 SQL이 여기 그대로 보이는 것이
 * Lua를 직접 쓰는 이 프로젝트의 결과 맞다. 좌석 페이즈에서 연관관계가 생기면 그때 다시 본다.
 */
@Repository
@RequiredArgsConstructor
public class MemberRepository {

    private final JdbcClient jdbcClient;

    /**
     * 인증에 필요한 것만 한 번에 읽는다. 해시 비교는 이 계층 밖에서 한다.
     */
    public Optional<Credentials> findByLoginId(String loginId) {
        return jdbcClient.sql("""
                        SELECT id, login_id, name, password
                        FROM member
                        WHERE login_id = :loginId
                        """)
                .param("loginId", loginId)
                .query(Credentials.class)
                .optional();
    }

    /**
     * 비밀번호 해시를 들고 다니는 유일한 자리. 이 타입은 member 패키지 밖으로 나가지 않는다.
     *
     * <p>레코드 생성자 매핑에 기대므로 SELECT의 컬럼 순서와 이름이 그대로 대응해야 한다.
     */
    public record Credentials(long id, String loginId, String name, String password) {

        public Member toMember() {
            return new Member(id, loginId, name);
        }
    }
}
