package com.urban6.waiting.member;

import java.io.Serializable;

/**
 * 인증을 통과한 회원. 비밀번호 해시는 담지 않는다.
 *
 * <p>세션에 그대로 들어가는 값이라, 해시를 필드로 두면 필요도 없는 것이 세션 저장소까지 따라간다.
 * WAS 다중화 페이즈에서 세션이 Redis로 옮겨가면 그 차이가 더 분명해진다.
 *
 * <p>{@code Serializable}은 세션이 Redis로 옮겨간 뒤부터 실제로 쓰인다 — 톰캣 인메모리 세션은
 * 직렬화를 거치지 않아 이게 없어도 지금까지 드러나지 않았을 뿐이다.
 */
public record Member(long id, String loginId, String name) implements Serializable {
    private static final long serialVersionUID = 1L;
}
