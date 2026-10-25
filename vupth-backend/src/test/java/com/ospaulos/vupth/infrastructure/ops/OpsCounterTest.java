package com.ospaulos.vupth.infrastructure.ops;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("admin-alertas-push-e-5xx: contador por hora, sem dado pessoal, limpeza sozinha")
class OpsCounterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-23T14:37:12Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("soma na hora cheia (14:00) do tipo, e limpa o que passa de 7 dias so uma vez por hora")
    void addsToTheHourAndPurgesOncePerHour() {
        OpsCounter counter = new OpsCounter(jdbc, clock);

        counter.add(OpsCounter.PUSH_REFUSED, 3);
        counter.increment(OpsCounter.PUSH_REFUSED);

        ArgumentCaptor<Timestamp> hour = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc, times(2)).update(anyString(), hour.capture(), eq("PUSH_REFUSED"), org.mockito.ArgumentMatchers.anyLong());
        assertThat(hour.getAllValues().get(0).toInstant()).isEqualTo(Instant.parse("2026-10-23T14:00:00Z"));
        ArgumentCaptor<Timestamp> limit = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc, times(1)).update(eq("DELETE FROM ops_counter WHERE hour < ?"), limit.capture());
        assertThat(limit.getValue().toInstant()).isEqualTo(Instant.parse("2026-10-16T14:00:00Z"));
    }

    @Test
    @DisplayName("zero nao conta; falha do banco nunca sobe")
    void zeroAndFailuresAreHarmless() {
        OpsCounter counter = new OpsCounter(jdbc, clock);
        counter.add(OpsCounter.PUSH_REFUSED, 0);
        verify(jdbc, never()).update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());

        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(Timestamp.class), anyString(),
                org.mockito.ArgumentMatchers.anyLong())).thenThrow(new IllegalStateException("banco fora"));
        counter.increment(OpsCounter.HTTP_5XX);
    }

    @Test
    @DisplayName("filtro: resposta 5xx conta; 2xx e 4xx nao; excecao que escapa conta e sobe de novo")
    void filterCountsOnlyServerErrors() throws Exception {
        OpsCounter counter = mock(OpsCounter.class);
        Http5xxCounterFilter filter = new Http5xxCounterFilter(counter);

        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), ok, new MockFilterChain());
        MockHttpServletResponse notFound = new MockHttpServletResponse();
        notFound.setStatus(404);
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), notFound, new MockFilterChain());
        verify(counter, never()).increment(anyString());

        MockHttpServletResponse error = new MockHttpServletResponse();
        error.setStatus(503);
        filter.doFilter(new MockHttpServletRequest("GET", "/x"), error, new MockFilterChain());
        verify(counter, times(1)).increment(OpsCounter.HTTP_5XX);

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
                (req, res) -> {
                    throw new ServletException("estourou");
                })).isInstanceOf(ServletException.class);
        verify(counter, times(2)).increment(OpsCounter.HTTP_5XX);
    }
}
