package com.urban6.waiting.member;

import java.io.Serializable;

/** 세션에 그대로 들어가는 값이라 비밀번호 해시는 담지 않는다. */
public record Member(long id, String loginId, String name) implements Serializable {
    private static final long serialVersionUID = 1L;
}
