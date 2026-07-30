package com.urban6.waiting.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <b>경로를 명시적으로 열거한다.</b> {@code "/**"} + exclude가 아니라 화이트리스트인 이유는
 * 측정 때문이다 — 대기열 API·정적 리소스·랜딩·actuator가 인터셉터를 <b>아예 타지 않아야</b>
 * 지금까지 잰 수치의 기준선이 유지된다.
 *
 * <p><b>보호할 화면이 늘어날 때마다 여기에 추가해야 한다. 빠뜨리면 그대로 뚫린다.</b>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdmissionGuard admissionGuard;
    private final LoginGuard loginGuard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 등록 순서가 실행 순서다. 입장권을 먼저 보고 로그인을 나중에 본다.
        // "/reservation"(조회 화면)과 "/reservations"(예약 POST)는 서로 매치하지 않는 별개 경로라
        // 둘 다 명시해야 한다. 빠뜨리면 예약 POST가 게이트를 타지 않는다.
        registry.addInterceptor(admissionGuard)
                .addPathPatterns("/login", "/reservation", "/reservations", "/reservations/*/cancel");

        registry.addInterceptor(loginGuard)
                .addPathPatterns("/reservation", "/reservations", "/reservations/*/cancel");
    }
}
