package com.ospaulos.vupth.unit.queue;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ospaulos.vupth.domain.shared.BusinessClock;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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

    private static final LocalDate TODAY = LocalDate.parse("2026-06-16");

    /**
     * Instantes deste teste sao escritos como RELOGIO DA LOJA (BUG2b): o {@code fixedStartTime} da
     * refeicao e hora de parede no fuso de negocio, entao "agora" tem de estar no mesmo relogio para
     * as relacoes do cenario (11:00 antes do almoco de 12:00) valerem.
     */
    private static OffsetDateTime at(String isoDate, String wallTime) {
        return BusinessClock.at(LocalDate.parse(isoDate), LocalTime.parse(wallTime));
    }

    private static final OffsetDateTime NOW = at("2026-06-16", "11:00");

    // ── helpers ──────────────────────────────────────────────────

    private Attendance att(int minutes) {
        Attendance a = mock(Attendance.class);
        when(a.getEstimatedTotalMinutes()).thenReturn(minutes);
        return a;
    }

    /** Recorrente previsto (nao iniciado): startTime null, usa fixedStartTime. */
    private ProfessionalInterval recurring(String time, int dur) {
        return ProfessionalInterval.createRecurring(null, dur, LocalTime.parse(time), "WEEKDAYS", "meal");
    }

    private ProfessionalInterval startedAt(OffsetDateTime actualStart, int dur) {
        ProfessionalInterval iv = recurring("12:00", dur);
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
        ProfessionalInterval iv = recurring("12:00", 60);
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
        var intervals = List.of(recurring("12:00", 60));
        assertThatCode(() -> QueueUtils.calculateWaitingTimeForPosition(queue, intervals, NOW, 3))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("previsto dentro da janela: soma a duracao da refeicao (cliente atendido apos 12:00)")
    void previsto_withinWindow_counts() {
        var queue = List.of(att(30), att(30), att(30)); // 11:00→11:30→12:00→12:30
        var intervals = List.of(recurring("12:00", 60));
        // pos 3: atendimentos cruzam 12:00 → almoco (60) entra
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, NOW, 3)).isEqualTo(150);
    }

    @Test
    @DisplayName("refeicao pulada hoje (Nao almocar — item #41) nao conta no ETA")
    void skippedToday_notCounted() {
        var queue = List.of(att(30), att(30), att(30)); // mesmo cenario do previsto_withinWindow (150)
        var meal = recurring("12:00", 60);
        meal.skipOn(TODAY); // dia do NOW
        // sem o almoco (pulado), so as 3 duracoes contam
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(meal), NOW, 3)).isEqualTo(90);
    }

    @Test
    @DisplayName("skip de OUTRO dia nao afeta o ETA de hoje (skip e day-scoped)")
    void skippedAnotherDay_stillCounts() {
        var queue = List.of(att(30), att(30), att(30));
        var meal = recurring("12:00", 60);
        meal.skipOn(TODAY.minusDays(1)); // ontem
        // pulou ontem, hoje a refeicao volta a contar → 150
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(meal), NOW, 3)).isEqualTo(150);
    }

    @Test
    @DisplayName("previsto nao alcancado: cliente atendido antes do horario → refeicao nao conta")
    void previsto_servedBeforeMeal_notCounted() {
        var queue = List.of(att(30)); // servido 11:30, antes do almoco 12:00
        var intervals = List.of(recurring("12:00", 60));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, NOW, 1)).isEqualTo(30);
    }

    @Test
    @DisplayName("some na proxima: agora 18:30, almoco (12:00) ja passou da janta (18:00) → some; so a janta conta")
    void previsto_disappearsAtNextMeal() {
        OffsetDateTime now = at("2026-06-16", "18:30");
        var queue = List.of(att(30));
        var intervals = List.of(recurring("12:00", 60), recurring("18:00", 60));
        // almoco some (projetado 18:30 >= janta 18:00); janta (60) conta
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, 1)).isEqualTo(90);
    }

    @Test
    @DisplayName("iniciado hoje (em pausa agora): conta so o tempo restante da refeicao")
    void startedToday_countsRemaining() {
        OffsetDateTime now = at("2026-06-16", "12:30");
        // iniciou 12:00, 60min → termina 13:00 → restam 30
        var intervals = List.of(startedAt(at("2026-06-16", "12:00"), 60));
        var queue = List.of(att(30));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, intervals, now, 1)).isEqualTo(60);
    }

    @Test
    @DisplayName("posicao 0 com refeicao EM CURSO: espera o restante dela, nao 'agora' (fila vazia ou nao)")
    void startedToday_positionZero_countsRemaining() {
        OffsetDateTime now = at("2026-06-16", "12:30");
        // iniciou 12:00, 60min → restam 30. Ninguem a frente, mas o profissional esta almocando.
        var intervals = List.of(startedAt(at("2026-06-16", "12:00"), 60));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(List.of(), intervals, now, 0)).isEqualTo(30);
        assertThat(QueueUtils.calculateWaitingTimeForPosition(List.of(att(30)), intervals, now, 0)).isEqualTo(30);
    }

    @Test
    @DisplayName("posicao 0 com pausa avulsa EM CURSO: espera o restante dela")
    void unexpectedInProgress_positionZero_countsRemaining() {
        // pausa 10:50/20min → termina 11:10; NOW 11:00 → restam 10
        var intervals = List.of(unexpected(at("2026-06-16", "10:50"), 20));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(List.of(), intervals, NOW, 0)).isEqualTo(10);
    }

    @Test
    @DisplayName("posicao 0: refeicao PREVISTA ja vencida e nao iniciada nao conta — so o que esta acontecendo")
    void previstoOverdue_positionZero_notCounted() {
        OffsetDateTime now = at("2026-06-16", "12:30");
        // almoco previsto 12:00, nao iniciado: o profissional esta trabalhando, a loja so projeta
        var intervals = List.of(recurring("12:00", 60));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(List.of(), intervals, now, 0)).isZero();
    }

    @Test
    @DisplayName("professionalEta com fila vazia e refeicao em curso: o snapshot da loja mostra o restante")
    void professionalEta_emptyQueue_mealInProgress() {
        OffsetDateTime now = at("2026-06-16", "12:30");
        var intervals = List.of(startedAt(at("2026-06-16", "12:00"), 60));
        assertThat(QueueUtils.professionalEta(List.of(), intervals, null, now)).isEqualTo(30);
    }

    @Test
    @DisplayName("day-scoped: intervalo iniciado ONTEM nao carrega (tratado como previsto hoje)")
    void startedYesterday_noCarryover() {
        // actualStartTime ontem, isActive=true, fixedStartTime 12:00 → hoje vira previsto
        var stale = startedAt(at("2026-06-15", "12:00"), 60);
        var queue = List.of(att(30)); // servido 11:30 < 12:00 → previsto nao conta
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(stale), NOW, 1)).isEqualTo(30);
    }

    @Test
    @DisplayName("refeicao ja feita hoje (encerrada) nao reaparece como previsto")
    void doneToday_notRecounted() {
        OffsetDateTime now = at("2026-06-16", "13:00");
        // almoco iniciado 12:00 e encerrado 12:45 hoje
        var done = doneToday(at("2026-06-16", "12:00"),
                at("2026-06-16", "12:45"));
        var queue = List.of(att(30));
        assertThat(QueueUtils.calculateWaitingTimeForPosition(queue, List.of(done), now, 1)).isEqualTo(30);
    }

    @Test
    @DisplayName("unexpected agendado durante o atendimento: empurrado para depois e somado")
    void unexpected_pushedAndCounted() {
        // pausa avulsa 11:15/20min, atendimento 30min comeca 11:00
        var intervals = List.of(unexpected(at("2026-06-16", "11:15"), 20));
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
        var intervals = List.of(recurringWithId("12:00", 60, id));
        assertThat(QueueUtils.mealsCountedForPosition(queue, intervals, NOW, 3)).containsExactly(id);
    }

    @Test
    @DisplayName("mealsCountedForPosition: refeicao nao alcancada nao e travada (set vazio)")
    void mealsCounted_notReached_empty() {
        var id = java.util.UUID.randomUUID();
        var queue = List.of(att(30)); // servido 11:30 < almoco 12:00
        var intervals = List.of(recurringWithId("12:00", 60, id));
        assertThat(QueueUtils.mealsCountedForPosition(queue, intervals, NOW, 1)).isEmpty();
    }

    @Test
    @DisplayName("mealsCountedForPosition: refeicao EM CURSO e travada para o primeiro da fila (pos 0)")
    void mealsCounted_startedMeal_positionZero_returnsId() {
        var id = java.util.UUID.randomUUID();
        var meal = startedAt(at("2026-06-16", "12:00"), 60);
        meal.setId(id);
        var now = at("2026-06-16", "12:30");
        assertThat(QueueUtils.mealsCountedForPosition(List.of(att(30)), List.of(meal), now, 0)).containsExactly(id);
    }

    @Test
    @DisplayName("mealsCountedForPosition: refeicao EM CURSO tambem e travada para quem entra atras de alguem")
    void mealsCounted_startedMeal_behindQueue_returnsId() {
        var id = java.util.UUID.randomUUID();
        var meal = startedAt(at("2026-06-16", "12:00"), 60);
        meal.setId(id);
        var now = at("2026-06-16", "12:30");
        assertThat(QueueUtils.mealsCountedForPosition(List.of(att(30), att(30)), List.of(meal), now, 1))
                .containsExactly(id);
    }

    @Test
    @DisplayName("mealsCountedForPosition: posicao 0 nao trava refeicao PREVISTA (a fila nao a alcanca)")
    void mealsCounted_positionZero_empty() {
        var intervals = List.of(recurringWithId("12:00", 60, java.util.UUID.randomUUID()));
        assertThat(QueueUtils.mealsCountedForPosition(List.of(att(30)), intervals, NOW, 0)).isEmpty();
    }

    @Test
    @DisplayName("lockedClientWait: restante do atual + atendimentos a frente + duracao cheia da refeicao travada")
    void lockedWait_prevista_fullDuration() {
        var ahead = List.of(att(30), att(30));
        var locked = List.of(recurring("12:00", 60)); // prevista hoje
        // 10 (atual) + 60 (a frente) + 60 (refeicao travada) = 130
        assertThat(QueueUtils.lockedClientWait(ahead, 10, locked, TODAY))
                .isEqualTo(130);
    }

    @Test
    @DisplayName("lockedClientWait: refeicao travada porem pulada no dia (#41) contribui 0")
    void lockedWait_skipped_contributesZero() {
        var ahead = List.of(att(30), att(30));
        var meal = recurring("12:00", 60);
        meal.skipOn(TODAY);
        // 0 + 60 + 0 (refeicao pulada cai mesmo travada) = 60
        assertThat(QueueUtils.lockedClientWait(ahead, 0, List.of(meal), TODAY))
                .isEqualTo(60);
    }

    @Test
    @DisplayName("lockedClientWait: refeicao travada porem ja encerrada hoje contribui 0")
    void lockedWait_doneToday_contributesZero() {
        var ahead = List.of(att(30));
        var done = doneToday(at("2026-06-16", "12:00"),
                at("2026-06-16", "12:45"));
        // 0 + 30 + 0 (ja consumida) = 30
        assertThat(QueueUtils.lockedClientWait(ahead, 0, List.of(done), TODAY))
                .isEqualTo(30);
    }

    @Test
    @DisplayName("lockedClientWait: null-safe (sem refeicoes travadas e sem atendimentos a frente)")
    void lockedWait_nullSafe() {
        assertThat(QueueUtils.lockedClientWait(null, 15, null, TODAY))
                .isEqualTo(15);
        assertThat(QueueUtils.lockedClientWait(List.of(), -5, List.of(), TODAY))
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
        assertThat(QueueUtils.lockedClientWait(ahead, 0, List.of(), TODAY))
                .isEqualTo(62);
    }

    // ── Item 40 — intencao de fila com horario-alvo (funcoes puras) ─────────────────

    private static final OffsetDateTime TARGET = at("2026-06-16", "17:00");

    @Test
    @DisplayName("isTargetFeasible: ETA atual mais cedo que o alvo -> viavel")
    void targetFeasible_etaBeforeTarget() {
        // agora 11:00 + 120min = 13:00 < 17:00 -> ha margem para segurar a intencao
        assertThat(QueueUtils.isTargetFeasible(NOW, 120, TARGET)).isTrue();
    }

    @Test
    @DisplayName("isTargetFeasible: fila ja preve atender em cima/depois do alvo -> recusa")
    void targetFeasible_etaAtOrAfterTarget() {
        // 11:00 + 360min = 17:00 (em cima) -> recusa
        assertThat(QueueUtils.isTargetFeasible(NOW, 360, TARGET)).isFalse();
        // 11:00 + 420min = 18:00 (depois) -> recusa
        assertThat(QueueUtils.isTargetFeasible(NOW, 420, TARGET)).isFalse();
    }

    @Test
    @DisplayName("isTargetFeasible: null-safe")
    void targetFeasible_nullSafe() {
        assertThat(QueueUtils.isTargetFeasible(null, 60, TARGET)).isFalse();
        assertThat(QueueUtils.isTargetFeasible(NOW, 60, null)).isFalse();
    }

    @Test
    @DisplayName("shouldConfirmIntent: previsto ainda antes do alvo -> nao confirma (segue cedendo)")
    void confirmIntent_stillBefore() {
        assertThat(QueueUtils.shouldConfirmIntent(NOW, 120, TARGET, 0)).isFalse();
    }

    @Test
    @DisplayName("shouldConfirmIntent: previsto cruzou o alvo -> confirma")
    void confirmIntent_crossed() {
        // 11:00 + 360 = 17:00 >= 17:00 -> confirma
        assertThat(QueueUtils.shouldConfirmIntent(NOW, 360, TARGET, 0)).isTrue();
        // 11:00 + 380 = 17:20 > 17:00 -> confirma
        assertThat(QueueUtils.shouldConfirmIntent(NOW, 380, TARGET, 0)).isTrue();
    }

    @Test
    @DisplayName("shouldConfirmIntent: tolerancia confirma quando 'encosta' antes do alvo")
    void confirmIntent_tolerance() {
        // 11:00 + 345 = 16:45; alvo - 15min = 16:45 -> encosta, confirma
        assertThat(QueueUtils.shouldConfirmIntent(NOW, 345, TARGET, 15)).isTrue();
        // 11:00 + 344 = 16:44 < 16:45 -> ainda nao
        assertThat(QueueUtils.shouldConfirmIntent(NOW, 344, TARGET, 15)).isFalse();
    }

    @Test
    @DisplayName("assignAgendaSlot: sem reservas -> pega o proprio alvo")
    void assignSlot_empty() {
        assertThat(QueueUtils.assignAgendaSlot(TARGET, 30, List.of())).isEqualTo(TARGET);
    }

    @Test
    @DisplayName("assignAgendaSlot: colisao no mesmo alvo -> proximo recebe alvo + duracao")
    void assignSlot_collision_chained() {
        var reserved = List.of(new QueueUtils.AgendaSlot(TARGET, 30)); // 17:00-17:30 ocupado
        // 2o pedido para 17:00, 30min -> 17:30
        assertThat(QueueUtils.assignAgendaSlot(TARGET, 30, reserved))
                .isEqualTo(at("2026-06-16", "17:30"));
    }

    @Test
    @DisplayName("assignAgendaSlot: cadeia de colisoes empurra ate o primeiro buraco livre")
    void assignSlot_chainOfCollisions() {
        var reserved = List.of(
                new QueueUtils.AgendaSlot(TARGET, 30),                                       // 17:00-17:30
                new QueueUtils.AgendaSlot(at("2026-06-16", "17:30"), 30)  // 17:30-18:00
        );
        assertThat(QueueUtils.assignAgendaSlot(TARGET, 30, reserved))
                .isEqualTo(at("2026-06-16", "18:00"));
    }

    @Test
    @DisplayName("assignAgendaSlot: alvo nao colide -> mantem o alvo mesmo com reservas adiante")
    void assignSlot_noOverlap_keepsTarget() {
        var reserved = List.of(
                new QueueUtils.AgendaSlot(at("2026-06-16", "18:00"), 30) // 18:00-18:30
        );
        // alvo 17:00 + 30 = 17:30, nao toca 18:00 -> mantem 17:00
        assertThat(QueueUtils.assignAgendaSlot(TARGET, 30, reserved)).isEqualTo(TARGET);
    }
}
