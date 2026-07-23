package com.urban6.waiting.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * <p><b>경로를 명시적으로 열거한다.</b> {@code addPathPatterns("/**")} + {@code exclude...}가
 * 아니라 화이트리스트인 이유는 측정 때문이다. 이렇게 두면 {@code /api/v1/waiting-queue/**},
 * 정적 리소스, 랜딩, actuator가 인터셉터를 <b>아예 타지 않는다</b>.
 * 지금까지 잰 진입 34k rps·조회 p95 수치의 기준선이 그대로 유지된다.
 *
 * <p>보호할 화면이 늘어날 때마다 여기에 한 줄씩 추가해야 한다. 빠뜨리면 그대로 뚫린다 —
 * 화이트리스트가 지불하는 대가다. 그래서 새 화면을 만들면 게이트를 거치는지 반드시 확인한다.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdmissionGuard admissionGuard;
    private final LoginGuard loginGuard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 등록 순서가 실행 순서다. 입장권을 먼저 보고 로그인을 나중에 본다.
        registry.addInterceptor(admissionGuard)
                .addPathPatterns("/login", "/reservation");

        registry.addInterceptor(loginGuard)
                .addPathPatterns("/reservation");
    }
}
