package com.urban6.waiting.member;

/**
 * 인증을 통과한 회원. 비밀번호 해시는 담지 않는다.
 *
 * <p>세션에 그대로 들어가는 값이라, 해시를 필드로 두면 필요도 없는 것이 세션 저장소까지 따라간다.
 * WAS 다중화 페이즈에서 세션이 Redis로 옮겨가면 그 차이가 더 분명해진다.
 */
public record Member(long id, String loginId, String name) {}
