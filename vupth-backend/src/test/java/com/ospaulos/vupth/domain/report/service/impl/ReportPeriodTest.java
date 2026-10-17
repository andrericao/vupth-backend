package com.ospaulos.vupth.domain.report.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** multi-loja-do-dono: o primeiro dia do periodo da serie (dia, semana de segunda a domingo, mes). */
class ReportPeriodTest {

    @Test
    @DisplayName("quarta 15/10/2025: dia e ele mesmo, semana comeca na segunda 13, mes no dia 1; o padrao e o dia")
    void periodStart() {
        LocalDate wednesday = LocalDate.of(2025, 10, 15);
        assertThat(ReportServiceImpl.periodStart(wednesday, "day")).isEqualTo(wednesday);
        assertThat(ReportServiceImpl.periodStart(wednesday, null)).isEqualTo(wednesday);
        assertThat(ReportServiceImpl.periodStart(wednesday, "week")).isEqualTo(LocalDate.of(2025, 10, 13));
        assertThat(ReportServiceImpl.periodStart(LocalDate.of(2025, 10, 19), "WEEK")).isEqualTo(LocalDate.of(2025, 10, 13));
        assertThat(ReportServiceImpl.periodStart(LocalDate.of(2025, 10, 13), "week")).isEqualTo(LocalDate.of(2025, 10, 13));
        assertThat(ReportServiceImpl.periodStart(wednesday, "month")).isEqualTo(LocalDate.of(2025, 10, 1));
    }
}
