package com.urban6.waiting.member;

/**
 * {@link com.urban6.waiting.queue.QueueException}과 같은 형태지만 {@code @RestControllerAdvice}는
 * 붙이지 않는다 — 로그인은 페이지 흐름이라 실패가 폼을 다시 그리는 것으로 드러나야 한다.
 */
public sealed class MemberException extends RuntimeException {

    private MemberException(String message) {
        super(message);
    }

    /**
     * 아이디가 없거나 비밀번호가 틀렸다.
     * <b>둘을 구분해 알려주면 어떤 아이디가 존재하는지 하나씩 확인할 수 있다.</b>
     */
    public static final class InvalidCredentials extends MemberException {
        public InvalidCredentials(String message) { super(message); }
    }
}
