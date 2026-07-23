package com.urban6.waiting.demo;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * {@code queue.demo.enabled: true}일 때만 빈으로 등록한다.
 *
 * <p>이 패키지의 클래스는 컴포넌트 스캔에 잡히므로 각자 조건을 달아야 한다.
 * {@code DemoConfig}에서 {@code @Bean}으로만 등록하는 방법도 있지만, Spring Framework 7의
 * {@code RequestMappingHandlerMapping.isHandler}는 {@code @Controller}가 붙은 타입만 핸들러로
 * 인정한다. 즉 컨트롤러에는 스테레오타입이 반드시 있어야 하고, 그러면 스캔을 피할 수 없다.
 *
 * <p>조건을 그대로 네 곳에 복사하면 하나만 빠뜨려도 "기능을 껐는데 스케줄러가 계속 도는"
 * 상태가 된다. 조건의 정의는 여기 한 곳에 두고, 붙이는 것만 각자 한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "queue.demo", name = "enabled", havingValue = "true")
public @interface ConditionalOnDemo {}
