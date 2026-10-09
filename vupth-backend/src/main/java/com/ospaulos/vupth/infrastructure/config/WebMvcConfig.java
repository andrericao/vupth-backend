package com.ospaulos.vupth.infrastructure.config;

import com.ospaulos.vupth.infrastructure.web.DeactivatedAccountInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Interceptadores do MVC: o "so consultar" da conta desativada (desativar-conta). */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final DeactivatedAccountInterceptor deactivatedAccountInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(deactivatedAccountInterceptor);
    }
}
