package com.urban6.waiting.member;

/**
 * queue의 {@link com.urban6.waiting.queue.QueueException}과 같은 형태지만
 * {@code @RestControllerAdvice}는 붙이지 않는다. 로그인은 JSON API가 아니라 페이지 흐름이라,
 * 실패는 상태 코드가 아니라 폼을 다시 그리는 것으로 드러나야 하기 때문이다.
 */
public sealed class MemberException extends RuntimeException {

    private MemberException(String message) {
        super(message);
    }

    /**
     * 아이디가 없거나 비밀번호가 틀렸다.
     *
     * <p><b>둘을 구분하지 않는 것이 핵심이다.</b> "없는 아이디입니다"와 "비밀번호가 틀렸습니다"를
     * 나눠서 알려주면, 공격자가 어떤 아이디가 존재하는지 하나씩 확인할 수 있다.
     */
    public static final class InvalidCredentials extends MemberException {
        public InvalidCredentials(String message) { super(message); }
    }
}
