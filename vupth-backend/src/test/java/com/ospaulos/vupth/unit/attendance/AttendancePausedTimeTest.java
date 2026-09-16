package com.ospaulos.vupth.unit.attendance;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests — tempo de servico com a pausa EM CURSO.
 *
 * O acumulado ({@code pausedDurationInMinutes}) so cresce quando a pausa termina (resume ou
 * finish). Enquanto o atendimento esta PAUSED, a pausa aberta ({@code lastPauseTime} → agora)
 * tambem nao e servico: o tempo de servico para no instante da pausa, e o que falta nao diminui.
 * Sem isso o ETA do cliente corria com ninguem sendo atendido (metade backend do A4).
 *
 * Horarios fixos (T0) para a conta nao depender do relogio da maquina.
 */
@DisplayName("Attendance — tempo de servico com pausa em curso")
class AttendancePausedTimeTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 9, 16, 14, 0, 0, 0, ZoneOffset.UTC);

    private Attendance attendance;

    @BeforeEach
    void setUp() {
        attendance = new Attendance();
        attendance.setStartTime(T0);
        attendance.setEstimatedMinutes(40);
        attendance.setPausedDurationInMinutes(0);
    }

    private void pausedAt(OffsetDateTime pauseTime) {
        attendance.setStatus(AttendanceStatus.PAUSED);
        attendance.setIsTimerActive(false);
        attendance.setLastPauseTime(pauseTime);
    }

    @Test
    @DisplayName("PAUSED: o servico para no instante da pausa")
    void paused_serviceStopsAtPause() {
        pausedAt(T0.plusMinutes(20));
        OffsetDateTime now = T0.plusMinutes(30);

        assertThat(attendance.getServiceDurationInMinutes(now)).isEqualTo(20);
        assertThat(attendance.getRemainingMinutes(now)).isEqualTo(20);
        assertThat(attendance.getPausedMinutes(now)).isEqualTo(10);
    }

    @Test
    @DisplayName("PAUSED: o tempo passando nao mexe no servico nem no que falta — so na pausa")
    void paused_clockMoving_onlyPauseGrows() {
        pausedAt(T0.plusMinutes(20));

        OffsetDateTime later = T0.plusMinutes(45);

        assertThat(attendance.getServiceDurationInMinutes(later)).isEqualTo(20);
        assertThat(attendance.getRemainingMinutes(later)).isEqualTo(20);
        assertThat(attendance.getPausedMinutes(later)).isEqualTo(25);
    }

    @Test
    @DisplayName("PAUSED pela segunda vez: desconta a pausa anterior e a aberta")
    void paused_again_countsAccumulatedAndOpenPause() {
        attendance.setPausedDurationInMinutes(5);
        pausedAt(T0.plusMinutes(25));
        OffsetDateTime now = T0.plusMinutes(30);

        assertThat(attendance.getServiceDurationInMinutes(now)).isEqualTo(20);
        assertThat(attendance.getRemainingMinutes(now)).isEqualTo(20);
        assertThat(attendance.getPausedMinutes(now)).isEqualTo(10);
    }

    @Test
    @DisplayName("IN_PROGRESS sem pausa aberta: so o acumulado sai (comportamento de antes)")
    void inProgress_onlyAccumulatedPause() {
        attendance.setStatus(AttendanceStatus.IN_PROGRESS);
        attendance.setIsTimerActive(true);
        attendance.setPausedDurationInMinutes(5);
        OffsetDateTime now = T0.plusMinutes(30);

        assertThat(attendance.getServiceDurationInMinutes(now)).isEqualTo(25);
        assertThat(attendance.getRemainingMinutes(now)).isEqualTo(15);
        assertThat(attendance.getPausedMinutes(now)).isEqualTo(5);
    }

    @Test
    @DisplayName("FINISHED: a duracao vai ate o endTime, independente da hora da leitura")
    void finished_usesEndTime() {
        attendance.setStatus(AttendanceStatus.FINISHED);
        attendance.setPausedDurationInMinutes(10);
        attendance.setEndTime(T0.plusMinutes(50));

        assertThat(attendance.getServiceDurationInMinutes(T0.plusMinutes(90))).isEqualTo(40);
        assertThat(attendance.getPausedMinutes(T0.plusMinutes(90))).isEqualTo(10);
    }
}
