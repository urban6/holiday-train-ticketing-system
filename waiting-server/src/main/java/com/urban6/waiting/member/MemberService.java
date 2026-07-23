package com.urban6.waiting.member;

import com.urban6.waiting.member.MemberRepository.Credentials;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberService {

    /**
     * 없는 아이디로 로그인을 시도했을 때 대신 비교할 해시.
     *
     * <p>아이디가 없다고 바로 돌아가면 그 응답만 유독 빠르다. BCrypt 한 번이 수십 밀리초라
     * 응답 시간 차이가 그대로 드러나서, 어떤 아이디가 존재하는지 밖에서 알아낼 수 있다
     * (user enumeration). 그래서 없을 때도 똑같이 한 번 돌린다.
     *
     * <p>실제 비밀번호를 인코딩한 값이 아니라, 어떤 입력으로도 맞을 수 없는 형식만 갖춘 해시다.
     */
    private static final String DUMMY_HASH =
            "$2a$10$7sMljHqcPUsgJyYoGwDMXe1aG2JMxBWNJ03RGGq/GWm2uURvX55Ka";

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 아이디와 비밀번호를 확인한다.
     *
     * <p>BCrypt는 의도적으로 느리다 — cost 10에서 수십 밀리초의 <b>CPU 시간</b>을 쓴다.
     * 가상 스레드는 블로킹을 잘 다루지만 CPU 연산은 캐리어 스레드를 그대로 점유하므로,
     * 이 메서드는 대기열 조회와 성격이 정반대인 부하다. 1인당 1회이고 활성 정원(queue.capacity)이
     * 이미 상한이라 지금은 문제가 없지만, 로그인 부하를 잴 때는 이 점을 알고 재야 한다.
     *
     * @throws MemberException.InvalidCredentials 아이디가 없거나 비밀번호가 틀렸다
     */
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
