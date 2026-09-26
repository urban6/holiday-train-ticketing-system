package com.urban6.waiting.member;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MemberRepository {

    private final JdbcClient jdbcClient;

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

    /** 비밀번호 해시를 들고 다니는 유일한 타입. member 패키지 밖으로 내보내지 않는다. */
    public record Credentials(long id, String loginId, String name, String password) {

        public Member toMember() {
            return new Member(id, loginId, name);
        }
    }
}
