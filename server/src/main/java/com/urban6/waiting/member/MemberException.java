package com.urban6.waiting.member;

/** 로그인은 페이지 흐름이라 ControllerAdvice 없이 폼을 다시 그려 실패를 알린다. */
public sealed class MemberException extends RuntimeException {

    private MemberException(String message) {
        super(message);
    }

    /** 아이디 없음과 비밀번호 틀림을 구분하지 않는다. 구분하면 존재하는 아이디가 드러난다. */
    public static final class InvalidCredentials extends MemberException {
        public InvalidCredentials(String message) { super(message); }
    }
}
