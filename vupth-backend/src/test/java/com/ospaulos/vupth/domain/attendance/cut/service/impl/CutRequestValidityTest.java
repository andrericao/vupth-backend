// Mesmo pacote da classe sob teste: expiresAtFor e package-private.
package com.ospaulos.vupth.domain.attendance.cut.service.impl;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** DEC-27: o pedido de hoje vale 5 min; o de um dia seguinte fica ativo ate o profissional decidir. */
class CutRequestValidityTest {

    private static final OffsetDateTime NOW = BusinessClock.at(LocalDate.of(2026, 10, 5), LocalTime.of(14, 0));

    @Test
    @DisplayName("sem dia escolhido: vale 5 min")
    void noDesiredTime_fiveMinutes() {
        assertThat(CutRequestServiceImpl.expiresAtFor(null, NOW)).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    @DisplayName("para hoje, mais tarde: vale 5 min")
    void today_fiveMinutes() {
        OffsetDateTime later = BusinessClock.at(LocalDate.of(2026, 10, 5), LocalTime.of(19, 0));
        assertThat(CutRequestServiceImpl.expiresAtFor(later, NOW)).isEqualTo(NOW.plusMinutes(5));
    }

    @Test
    @DisplayName("para amanha: sem vencimento (null)")
    void nextDay_noExpiry() {
        OffsetDateTime tomorrow = BusinessClock.at(LocalDate.of(2026, 10, 6), LocalTime.of(9, 0));
        assertThat(CutRequestServiceImpl.expiresAtFor(tomorrow, NOW)).isNull();
    }

    @Test
    @DisplayName("o dia e o da loja: 23:30 de hoje no relogio da loja ainda e hoje, mesmo ja sendo amanha em UTC")
    void storeDayNotUtc() {
        OffsetDateTime lateToday = BusinessClock.at(LocalDate.of(2026, 10, 5), LocalTime.of(23, 30));
        assertThat(CutRequestServiceImpl.expiresAtFor(lateToday, NOW)).isEqualTo(NOW.plusMinutes(5));
    }
}
