package com.ospaulos.vupth.unit.sentry;

import io.sentry.spring.boot.jakarta.SentryProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** sentry-desempenho-amostrado: o rastreio nasce desligado, liga por variavel e nunca olha o /actuator. */
@DisplayName("Sentry: rastreio de desempenho em amostra baixa, ligado por SENTRY_TRACES_SAMPLE_RATE")
class SentryTracesConfigTest {

    private static SentryProperties bind(Map<String, Object> envVars) throws IOException {
        Properties app = PropertiesLoaderUtils.loadProperties(new ClassPathResource("config/application.properties"));
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new PropertiesPropertySource("app", app));
        env.getPropertySources().addFirst(new MapPropertySource("vars", envVars));
        return Binder.get(env).bind("sentry", SentryProperties.class).get();
    }

    @Test
    @DisplayName("sem a variavel a amostra e 0: nada muda e a cota gratis fica intacta")
    void offByDefault() throws IOException {
        assertThat(bind(Map.of()).getTracesSampleRate()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("com SENTRY_TRACES_SAMPLE_RATE=0.05 a amostra e 5%")
    void turnedOnByVariable() throws IOException {
        assertThat(bind(Map.of("SENTRY_TRACES_SAMPLE_RATE", "0.05")).getTracesSampleRate()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("o /actuator fica fora do rastreio e o resto das transacoes nao")
    void actuatorIsIgnored() throws IOException {
        var ignored = bind(Map.of()).getIgnoredTransactions();
        assertThat(ignored).isNotEmpty();
        assertThat(ignored.stream().anyMatch(rx -> "GET /actuator/health/readiness".matches(rx.getFilterString()))).isTrue();
        assertThat(ignored.stream().anyMatch(rx -> "GET /actuator/prometheus".matches(rx.getFilterString()))).isTrue();
        assertThat(ignored.stream().anyMatch(rx -> "GET /attendances/{id}".matches(rx.getFilterString()))).isFalse();
    }

    @Test
    @DisplayName("continua sem dado pessoal")
    void noPii() throws IOException {
        assertThat(bind(Map.of()).isSendDefaultPii()).isFalse();
    }
}
