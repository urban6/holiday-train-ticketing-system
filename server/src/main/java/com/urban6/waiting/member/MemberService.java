package com.urban6.waiting.member;

import com.urban6.waiting.member.MemberRepository.Credentials;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberService {

    /** 없는 아이디도 같은 시간을 쓰게 대신 비교할 해시. 바로 돌아가면 응답 시간으로 아이디 존재가 드러난다. */
    private static final String DUMMY_HASH =
            "$2a$10$7sMljHqcPUsgJyYoGwDMXe1aG2JMxBWNJ03RGGq/GWm2uURvX55Ka";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    /** BCrypt는 수십 ms의 CPU를 쓰고, 가상 스레드여도 그동안 캐리어 스레드를 점유한다. */
    public Member authenticate(String loginId, String rawPassword) {
        Optional<Credentials> found = memberRepository.findByLoginId(loginId);

        String hash = found.map(Credentials::password).orElse(DUMMY_HASH);
        boolean matches = passwordEncoder.matches(rawPassword, hash);

        if (found.isEmpty() || !matches) {
            throw new MemberException.InvalidCredentials("아이디 또는 비밀번호가 올바르지 않습니다.");
        }
        return found.get().toMember();
    }
}
