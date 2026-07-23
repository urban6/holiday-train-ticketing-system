package com.urban6.waiting.member;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class MemberConfig {

    /**
     * Spring Security 스타터를 쓰지 않으므로 이 빈은 자동으로 생기지 않는다.
     *
     * <p>기본 cost(10)를 그대로 둔다. 올리면 대입 공격 비용이 두 배씩 오르지만
     * 서버 CPU 비용도 똑같이 두 배가 된다. 로그인 처리량을 실제로 재 보기 전에는
     * 근거 없이 올릴 값이 아니다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
