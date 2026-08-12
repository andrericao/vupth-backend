package com.ospaulos.vupth.unit.shared;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — recorte de PERIODO no fuso da loja (backlog #33, residuo do BUG2b).
 *
 * <p>O BUG2b migrou horario de funcionamento e refeicao para relogio de parede, mas <b>nao</b> os
 * filtros de periodo: {@code ReportServiceImpl} e {@code AttendanceServiceImpl} montavam a janela
 * com {@code dateFrom.atStartOfDay().atOffset(UTC)}. Em {@code America/Sao_Paulo} isso faz o dia
 * comecar as <b>21h da vespera</b> — o atendimento das 22h caia no relatorio do dia seguinte, e
 * quanto mais tarde a loja fecha, mais o numero do dia mente.</p>
 *
 * <p>Estes testes sao a regressao <b>deterministica</b> do item. O
 * {@code ReportIntegrationTest.establishmentReport_periodToday} tambem pega o bug, mas so quando
 * roda entre 21h e meia-noite num host atras do UTC — no runner do CI, que e UTC, ele passa
 * mesmo com o bug presente. Aqui as datas sao fixas, entao o resultado nao depende de quando nem
 * de onde a suite roda.</p>
 */
class BusinessDayRangeTest {

    /** Um dia qualquer sem nada de especial — o Brasil nao tem horario de verao desde 2019. */
    private static final LocalDate DIA = LocalDate.of(2026, 8, 11);

    @Test
    @DisplayName("O dia da loja comeca a meia-noite LOCAL, nao a meia-noite UTC")
    void startOfDayIsLocalMidnight() {
        OffsetDateTime inicio = BusinessClock.startOfDay(DIA);

        assertThat(inicio.toLocalDate()).isEqualTo(DIA);
        assertThat(inicio.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
        assertThat(inicio.getOffset()).isEqualTo(ZoneOffset.ofHours(-3));
        // O mesmo instante em UTC: 03:00Z. A formula antiga usava 00:00Z, tres horas antes.
        assertThat(inicio.withOffsetSameInstant(ZoneOffset.UTC).toString()).isEqualTo("2026-08-11T03:00Z");
    }

    @Test
    @DisplayName("dateTo e inclusivo no dia: o teto e a meia-noite local do dia SEGUINTE")
    void endOfDayIsNextLocalMidnightExclusive() {
        OffsetDateTime fim = BusinessClock.endOfDayExclusive(DIA);

        assertThat(fim.toLocalDate()).isEqualTo(DIA.plusDays(1));
        assertThat(fim.toLocalTime()).isEqualTo(java.time.LocalTime.MIDNIGHT);
        assertThat(fim.withOffsetSameInstant(ZoneOffset.UTC).toString()).isEqualTo("2026-08-12T03:00Z");
    }

    @Test
    @DisplayName("REGRESSAO #33 — o atendimento das 22h entra no dia em que aconteceu")
    void lateNightAttendanceBelongsToTheDayItHappened() {
        // 22h do dia 11 no relogio da loja = 01:00Z do dia 12. A janela ANTIGA ([00:00Z do dia 11,
        // 00:00Z do dia 12)) deixava este atendimento de FORA do relatorio do dia 11 e o jogava no
        // do dia 12 — o dono fechava o caixa e o corte das 22h nao estava la.
        OffsetDateTime corteDas22h = OffsetDateTime.of(2026, 8, 11, 22, 0, 0, 0, ZoneOffset.ofHours(-3));

        OffsetDateTime inicio = BusinessClock.startOfDay(DIA);
        OffsetDateTime fim = BusinessClock.endOfDayExclusive(DIA);

        assertThat(corteDas22h).isAfterOrEqualTo(inicio).isBefore(fim);

        // E a prova de que a formula antiga realmente errava — se um dia alguem "simplificar" o
        // BusinessClock de volta para UTC, este assert cai junto com o de cima.
        OffsetDateTime tetoAntigoUTC = DIA.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        assertThat(corteDas22h).isAfterOrEqualTo(tetoAntigoUTC);
    }

    @Test
    @DisplayName("A vespera das 23h NAO entra no dia seguinte")
    void previousEveningStaysOut() {
        // O espelho do caso acima: com a janela em UTC, as 23h do dia 10 (= 02:00Z do dia 11)
        // entravam indevidamente no dia 11.
        OffsetDateTime vespera = OffsetDateTime.of(2026, 8, 10, 23, 0, 0, 0, ZoneOffset.ofHours(-3));

        assertThat(vespera).isBefore(BusinessClock.startOfDay(DIA));

        OffsetDateTime pisoAntigoUTC = DIA.atStartOfDay().atOffset(ZoneOffset.UTC);
        assertThat(vespera).isAfterOrEqualTo(pisoAntigoUTC);
    }

    @Test
    @DisplayName("Janela de um unico dia cobre exatamente 24h, sem buraco entre dias consecutivos")
    void consecutiveDaysTileWithoutGap() {
        // Teto exclusivo em vez de 23:59:59: sem buraco de um segundo e sem depender da precisao
        // da coluna. O teto de um dia e exatamente o piso do proximo.
        assertThat(BusinessClock.endOfDayExclusive(DIA))
                .isEqualTo(BusinessClock.startOfDay(DIA.plusDays(1)));

        assertThat(java.time.Duration.between(
                BusinessClock.startOfDay(DIA), BusinessClock.endOfDayExclusive(DIA)))
                .isEqualTo(java.time.Duration.ofHours(24));
    }
}
