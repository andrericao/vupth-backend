package com.ospaulos.vupth.unit.queue;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests — QueueUtils.calculateWaitingTimeForPosition (spec item #11d).
 *
 * Funcao pura: queue de atendimentos + intervalos do profissional + agora + posicao.
 * Cobre intervalos recorrentes previstos (projecao em hoje, desloca pra frente,
 * some na proxima), iniciados hoje (day-scoped), unexpected, e null-safety.
 */
class QueueUtilsTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-06-16T11:00:00Z");

    // ── helpers ──────────────────────────────────────────────────

    private Attendance att(int minutes) {
        Attendance a = mock(Attendance.class);
        when(a.getEstimatedTotalMinutes()).thenReturn(minutes);
        return a;
    }

    /** Recorrente previsto (nao iniciado): startTime null, usa fixedStartTime. */
    private ProfessionalInterval recurring(String time, int dur) {
        return ProfessionalInterval.createRecurring(null, dur, OffsetTime.parse(time), "WEEKDAYS", "meal");
    }

    private ProfessionalInterval startedAt(OffsetDateTime actualStart, int dur) {
        ProfessionalInterval iv = recurring("12:00:00Z", dur);
        iv.setActualStartTime(actualStart);
        iv.setIsActive(true);
        iv.setPlannedEndTime(actualStart.plusMinutes(dur));
        return iv;
    }

    private ProfessionalInterval unexpected(OffsetDateTime start, int dur) {
        return ProfessionalInterval.createUnexpected(null, dur, start, "break");
    }

    /** Refeicao recorrente iniciada e ENCERRADA hoje (ja consumida). */
    private ProfessionalInterval doneToday(OffsetDateTime start, OffsetDateTime end) {
        ProfessionalInterval iv = recurring("12:00:00Z", 60);
        iv.setActualStartTime(start);
        iv.setActualEndTime(end);
        iv.setIsActive(false);
        return iv;
    }

    // ── testes ───────────────────────────────────────────────────

    @Test
    @DisplayName("sem intervalos: espera = soma das duracoes a frente")
    void noIntervals_sumsDurations() {
        var queue = List.of(att(30), att(30), att(30));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(), NOW, 3)).isEqualTo(90);
    }

    @Test
    @DisplayName("posicao 0 e fila vazia retornam 0")
    void zeroPositionOrEmpty() {
        assertThat(QueueUtils.calculateWaitingTimeForPosition(List.of(att(30)), List.of(), NOW, 0)).isZero();
        assertThat(QueueUtils.calculateWaitingTimeForPosition(List.of(), List.of(), NOW, 0)).isZero();
    }

    @Test
    @DisplayName("recorrente null-safe: nao lanca NPE (usa fixedStartTime, nao startTime)")
    void recurring_nullStartTime_doesNotThrow() {
        var queue = List.of(att(30), att(30), att(30));
        var intervals = List.of(recurring("12:00:00Z", 60));
        assertThatCode(() -> QueueUtils.calculateWaitingTimeForPosition(queue, intervals, NOW, 3))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("previsto dentro da janela: soma a duracao da refeicao (cliente atendido apos 12:00)")
    void previsto_withinWindow_counts() {
        var queue = List.of(att(30), att(30), att(30)); // 11:00→11:30→12:00→12:30
        var intervals = List.of(recurring("12:00:00Z", 60));
        // pos 3: atendimentos cruzam 12:00 → almoco (60) entra
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, NOW, 3)).isEqualTo(150);
    }

    @Test
    @DisplayName("refeicao pulada hoje (Nao almocar — item #41) nao conta no ETA")
    void skippedToday_notCounted() {
        var queue = List.of(att(30), att(30), att(30)); // mesmo cenario do previsto_withinWindow (150)
        var meal = recurring("12:00:00Z", 60);
        meal.skipOn(java.time.LocalDate.parse("2026-06-16")); // dia do NOW
        // sem o almoco (pulado), so as 3 duracoes contam
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(meal), NOW, 3)).isEqualTo(90);
    }

    @Test
    @DisplayName("skip de OUTRO dia nao afeta o ETA de hoje (skip e day-scoped)")
    void skippedAnotherDay_stillCounts() {
        var queue = List.of(att(30), att(30), att(30));
        var meal = recurring("12:00:00Z", 60);
        meal.skipOn(java.time.LocalDate.parse("2026-06-15")); // ontem
        // pulou ontem, hoje a refeicao volta a contar → 150
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(meal), NOW, 3)).isEqualTo(150);
    }

    @Test
    @DisplayName("previsto nao alcancado: cliente atendido antes do horario → refeicao nao conta")
    void previsto_servedBeforeMeal_notCounted() {
        var queue = List.of(att(30)); // servido 11:30, antes do almoco 12:00
        var intervals = List.of(recurring("12:00:00Z", 60));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, NOW, 1)).isEqualTo(30);
    }

    @Test
    @DisplayName("some na proxima: agora 18:30, almoco (12:00) ja passou da janta (18:00) → some; so a janta conta")
    void previsto_disappearsAtNextMeal() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-16T18:30:00Z");
        var queue = List.of(att(30));
        var intervals = List.of(recurring("12:00:00Z", 60), recurring("18:00:00Z", 60));
        // almoco some (projetado 18:30 >= janta 18:00); janta (60) conta
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, 1)).isEqualTo(90);
    }

    @Test
    @DisplayName("iniciado hoje (em pausa agora): conta so o tempo restante da refeicao")
    void startedToday_countsRemaining() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-16T12:30:00Z");
        // iniciou 12:00, 60min → termina 13:00 → restam 30
        var intervals = List.of(startedAt(OffsetDateTime.parse("2026-06-16T12:00:00Z"), 60));
        var queue = List.of(att(30));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, 1)).isEqualTo(60);
    }

    @Test
    @DisplayName("day-scoped: intervalo iniciado ONTEM nao carrega (tratado como previsto hoje)")
    void startedYesterday_noCarryover() {
        // actualStartTime ontem, isActive=true, fixedStartTime 12:00 → hoje vira previsto
        var stale = startedAt(OffsetDateTime.parse("2026-06-15T12:00:00Z"), 60);
        var queue = List.of(att(30)); // servido 11:30 < 12:00 → previsto nao conta
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(stale), NOW, 1)).isEqualTo(30);
    }

    @Test
    @DisplayName("refeicao ja feita hoje (encerrada) nao reaparece como previsto")
    void doneToday_notRecounted() {
        OffsetDateTime now = OffsetDateTime.parse("2026-06-16T13:00:00Z");
        // almoco iniciado 12:00 e encerrado 12:45 hoje
        var done = doneToday(OffsetDateTime.parse("2026-06-16T12:00:00Z"),
                OffsetDateTime.parse("2026-06-16T12:45:00Z"));
        var queue = List.of(att(30));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(done), now, 1)).isEqualTo(30);
    }

    @Test
    @DisplayName("unexpected agendado durante o atendimento: empurrado para depois e somado")
    void unexpected_pushedAndCounted() {
        // pausa avulsa 11:15/20min, atendimento 30min comeca 11:00
        var intervals = List.of(unexpected(OffsetDateTime.parse("2026-06-16T11:15:00Z"), 20));
        var queue = List.of(att(30), att(30));
        // i=0: att 11:00→11:30 (pausa 11:15 empurrada p/ 11:30); i=1 drena pausa(20)+att(30)
        // total = 30 + 20 + 30 = 80
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, NOW, 2)).isEqualTo(80);
    }

    // ── lock da refeicao no ingresso (item #37) ──────────────────

    /** Recorrente previsto com id (necessario para o lock identificar a refeicao). */
    private ProfessionalInterval recurringWithId(String time, int dur, java.util.UUID id) {
        ProfessionalInterval iv = recurring(time, dur);
        iv.setId(id);
        return iv;
    }

    @Test
    @DisplayName("mealsCountedForPosition: refeicao prevista dentro da janela e travada (retorna id)")
    void mealsCounted_withinWindow_returnsId() {
        var id = java.util.UUID.randomUUID();
        var queue = List.of(att(30), att(30), att(30)); // cruzam 12:00
        var intervals = List.of(recurringWithId("12:00:00Z", 60, id));
        assertThat(QueueUtils.mealsCountedForPosition(queue, intervals, NOW, 3)).containsExactly(id);
    }

    @Test
    @DisplayName("mealsCountedForPosition: refeicao nao alcancada nao e travada (set vazio)")
    void mealsCounted_notReached_empty() {
        var id = java.util.UUID.randomUUID();
        var queue = List.of(att(30)); // servido 11:30 < almoco 12:00
        var intervals = List.of(recurringWithId("12:00:00Z", 60, id));
        assertThat(QueueUtils.mealsCountedForPosition(queue, intervals, NOW, 1)).isEmpty();
    }

    @Test
    @DisplayName("mealsCountedForPosition: posicao 0 nao trava nada")
    void mealsCounted_positionZero_empty() {
        var intervals = List.of(recurringWithId("12:00:00Z", 60, java.util.UUID.randomUUID()));
        assertThat(QueueUtils.mealsCountedForPosition(List.of(att(30)), intervals, NOW, 0)).isEmpty();
    }

    @Test
    @DisplayName("lockedClientWait: restante do atual + atendimentos a frente + duracao cheia da refeicao travada")
    void lockedWait_prevista_fullDuration() {
        var ahead = List.of(att(30), att(30));
        var locked = List.of(recurring("12:00:00Z", 60)); // prevista hoje
        // 10 (atual) + 60 (a frente) + 60 (refeicao travada) = 130
        assertThat(QueueUtils.lockedClientWait(ahead, 10, locked, java.time.LocalDate.parse("2026-06-16")))
                .isEqualTo(130);
    }

    @Test
    @DisplayName("lockedClientWait: refeicao travada porem pulada no dia (#41) contribui 0")
    void lockedWait_skipped_contributesZero() {
        var ahead = List.of(att(30), att(30));
        var meal = recurring("12:00:00Z", 60);
        meal.skipOn(java.time.LocalDate.parse("2026-06-16"));
        // 0 + 60 + 0 (refeicao pulada cai mesmo travada) = 60
        assertThat(QueueUtils.lockedClientWait(ahead, 0, List.of(meal), java.time.LocalDate.parse("2026-06-16")))
                .isEqualTo(60);
    }

    @Test
    @DisplayName("lockedClientWait: refeicao travada porem ja encerrada hoje contribui 0")
    void lockedWait_doneToday_contributesZero() {
        var ahead = List.of(att(30));
        var done = doneToday(OffsetDateTime.parse("2026-06-16T12:00:00Z"),
                OffsetDateTime.parse("2026-06-16T12:45:00Z"));
        // 0 + 30 + 0 (ja consumida) = 30
        assertThat(QueueUtils.lockedClientWait(ahead, 0, List.of(done), java.time.LocalDate.parse("2026-06-16")))
                .isEqualTo(30);
    }

    @Test
    @DisplayName("lockedClientWait: null-safe (sem refeicoes travadas e sem atendimentos a frente)")
    void lockedWait_nullSafe() {
        assertThat(QueueUtils.lockedClientWait(null, 15, null, java.time.LocalDate.parse("2026-06-16")))
                .isEqualTo(15);
        assertThat(QueueUtils.lockedClientWait(List.of(), -5, List.of(), java.time.LocalDate.parse("2026-06-16")))
                .isZero(); // currentRemaining negativo e tratado como 0
    }

    // ── Encaixe / ramp (item #38) ────────────────────────────────

    /** Atendimento encaixado (isFit): contribui rampado. D=minutes, length inicial=aheadLength. */
    private Attendance fitAtt(int minutes, int aheadLength) {
        Attendance a = mock(Attendance.class);
        when(a.getEstimatedTotalMinutes()).thenReturn(minutes);
        when(a.isFit()).thenReturn(true);
        when(a.getFitAheadLength()).thenReturn(aheadLength);
        return a;
    }

    @Test
    @DisplayName("effective: atendimento NORMAL contribui sempre o tempo cheio (sem ramp)")
    void effective_normal_full() {
        var normal = att(30);
        assertThat(QueueUtils.effectiveEstimatedMinutes(normal, 0)).isEqualTo(30);
        assertThat(QueueUtils.effectiveEstimatedMinutes(normal, 9)).isEqualTo(30);
    }

    @Test
    @DisplayName("effective: encaixe no instante (index == length) contribui 0 — ninguem sente")
    void effective_fit_atInsertion_zero() {
        // D=40, length=5, recem-encaixado -> 5 clientes ainda a frente -> 0 cotas maduras
        assertThat(QueueUtils.effectiveEstimatedMinutes(fitAtt(40, 5), 5)).isZero();
    }

    @Test
    @DisplayName("effective: ramp linear sobe uma fatia (D/length) a cada cliente que sai da frente")
    void effective_fit_rampSteps() {
        // D=40, length=5 -> fatia = 8 min. index = clientes a frente agora.
        Attendance fit = fitAtt(40, 5);
        assertThat(QueueUtils.effectiveEstimatedMinutes(fit, 5)).isEqualTo(0);   // 0 cotas
        assertThat(QueueUtils.effectiveEstimatedMinutes(fit, 4)).isEqualTo(8);   // 1 cota
        assertThat(QueueUtils.effectiveEstimatedMinutes(fit, 3)).isEqualTo(16);  // 2 cotas
        assertThat(QueueUtils.effectiveEstimatedMinutes(fit, 2)).isEqualTo(24);  // 3 cotas
        assertThat(QueueUtils.effectiveEstimatedMinutes(fit, 1)).isEqualTo(32);  // 4 cotas
        assertThat(QueueUtils.effectiveEstimatedMinutes(fit, 0)).isEqualTo(40);  // boca da fila = D cheio
    }

    @Test
    @DisplayName("effective: na boca da fila (index 0) e alem do length o encaixe contribui o D cheio")
    void effective_fit_atFrontFull() {
        assertThat(QueueUtils.effectiveEstimatedMinutes(fitAtt(40, 5), 0)).isEqualTo(40);
        // index negativo (clamp) tambem -> cheio
        assertThat(QueueUtils.effectiveEstimatedMinutes(fitAtt(40, 5), -1)).isEqualTo(40);
    }

    @Test
    @DisplayName("effective: encaixe sem ninguem a frente (length 0) contribui cheio desde o inicio")
    void effective_fit_zeroLength_full() {
        assertThat(QueueUtils.effectiveEstimatedMinutes(fitAtt(40, 0), 0)).isEqualTo(40);
    }

    @Test
    @DisplayName("effective: null-safe (atendimento nulo -> 0)")
    void effective_nullSafe() {
        assertThat(QueueUtils.effectiveEstimatedMinutes(null, 0)).isZero();
    }

    @Test
    @DisplayName("lockedClientWait: encaixe a frente entra rampado conforme a posicao dele na fila")
    void lockedWait_withFit_ramped() {
        // fila a frente do cliente: [normal 30, encaixe(D=40,len=5) na posicao 1]
        // o encaixe esta no index 1 -> 4 cotas maduras de fatia 8 -> contribui 32
        var ahead = List.of(att(30), fitAtt(40, 5));
        // 0 (atual) + 30 (normal) + 32 (encaixe rampado) = 62
        assertThat(QueueUtils.lockedClientWait(ahead, 0, List.of(), java.time.LocalDate.parse("2026-06-16")))
                .isEqualTo(62);
    }
}
