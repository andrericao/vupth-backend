package com.ospaulos.vupth.domain.admin.service;

import com.ospaulos.vupth.api.admin.dto.AdminMetricsDtos.Window;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** painel, fatia 2: as janelas do periodo e da comparacao. */
class AdminMetricsServiceTest {

    private final Clock clock = Clock.fixed(OffsetDateTime.parse("2026-10-17T15:00:00-03:00").toInstant(), ZoneOffset.UTC);
    private final AdminMetricsService service = new AdminMetricsService(mock(JdbcTemplate.class), clock);

    @Test
    @DisplayName("sem datas: do comeco do dia da loja ate agora; com datas, as dadas")
    void window() {
        Window today = service.window(null, null);
        assertThat(today.from().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-17T00:00:00-03:00").toInstant());
        assertThat(today.to().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-17T15:00:00-03:00").toInstant());

        OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00-03:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-10-08T00:00:00-03:00");
        assertThat(service.window(from, to)).isEqualTo(new Window(from, to));
    }

    @Test
    @DisplayName("comparacao: mesmo dia da semana passada (padrao), periodo anterior do mesmo tamanho, ou nenhuma")
    void compare() {
        Window w = new Window(OffsetDateTime.parse("2026-10-15T00:00:00-03:00"), OffsetDateTime.parse("2026-10-17T00:00:00-03:00"));

        assertThat(service.compareWindow(w, "same_weekday_last_week"))
                .isEqualTo(new Window(w.from().minusDays(7), w.to().minusDays(7)));
        assertThat(service.compareWindow(w, null)).isEqualTo(new Window(w.from().minusDays(7), w.to().minusDays(7)));
        assertThat(service.compareWindow(w, "previous_period"))
                .isEqualTo(new Window(w.from().minusDays(2), w.from()));
        assertThat(service.compareWindow(w, "NONE")).isNull();
    }
}
