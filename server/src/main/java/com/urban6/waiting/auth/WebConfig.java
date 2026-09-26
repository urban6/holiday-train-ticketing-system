package com.urban6.waiting.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 대기열 API가 인터셉터를 아예 타지 않도록 {@code "/**"} 대신 경로를 명시한다.
 * <b>보호할 경로를 추가하면 여기에도 등록해야 한다. 빠뜨리면 그대로 뚫린다.</b>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AdmissionGuard admissionGuard;
    private final LoginGuard loginGuard;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 등록 순서가 실행 순서다. 입장권을 먼저 본다.
        // "/reservation"과 "/reservations"는 서로 매치하지 않으므로 둘 다 적는다.
        registry.addInterceptor(admissionGuard)
                .addPathPatterns("/login", "/reservation", "/reservations", "/reservations/*/cancel");

        registry.addInterceptor(loginGuard)
                .addPathPatterns("/reservation", "/reservations", "/reservations/*/cancel");
    }
}
