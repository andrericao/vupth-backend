package com.ospaulos.vupth.infrastructure.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/**
 * estudo-desempenho (DEC-145/146, proposta 3): ETag so nas leituras publicas e estaveis. O app que repete o pedido manda
 * {@code If-None-Match} e recebe 304 sem corpo (menos bytes no 4G). So estas rotas: a fila em tempo real, o pagamento e tudo que e do
 * usuario ficam de fora. O hash e do corpo ja pronto, entao o servidor ainda monta a resposta; o ganho e de rede, nao de CPU.
 */
@Configuration
public class HttpCacheConfig {

    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/payment-gateway", "/professional/*/hours", "/establishment/*/hours");
        registration.setName("etagFilter");
        return registration;
    }
}
