package com.ospaulos.vupth.domain.queue.service;

import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Estimativa de espera da fila, considerando os intervalos (refeicoes/pausas) do
 * profissional. Recorrente-aware e null-safe (spec item #11d).
 *
 * Regras dos intervalos na estimativa (projecao):
 *  - <b>Iniciado hoje</b> (day-scoped): bloco real em andamento; conta o tempo restante.
 *  - <b>Recorrente previsto</b> (nao iniciado hoje): projetado em hoje@fixedStartTime,
 *    <i>desloca pra frente sempre</i> (nunca no passado; empurrado pelos atendimentos) e
 *    <i>some quando alcanca a proxima refeicao</i> (almoco some no inicio da janta;
 *    janta some no fim do dia). So afeta o numero estimado, nunca o registro.
 *  - <b>Unexpected</b>: pausa avulsa num horario; empurrada para depois do atendimento corrente.
 *
 * Nunca muta a entidade — toda a aritmetica usa projecoes locais ({@link Block}).
 *
 * <p>Para o <b>ETA estavel do cliente</b> (item #37 — lock), ver {@link #mealsCountedForPosition}
 * (quais refeicoes travar no ingresso) e {@link #lockedClientWait} (calculo a partir do lock).
 */
public class QueueUtils {

    /** Projecao local de um intervalo na linha do tempo de hoje (nao persiste). */
    private static final class Block {
        OffsetDateTime start;             // mutavel: desloca durante a simulacao
        final int durationMinutes;
        final OffsetDateTime disappearAt; // recorrente previsto: some ao alcancar isto; null = nunca some
        final boolean previsto;           // recorrente nao iniciado (deslocavel/cancelavel)
        final UUID intervalId;            // id da refeicao recorrente prevista (null p/ iniciado/unexpected)

        Block(OffsetDateTime start, int durationMinutes, OffsetDateTime disappearAt,
              boolean previsto, UUID intervalId) {
            this.start = start;
            this.durationMinutes = durationMinutes;
            this.disappearAt = disappearAt;
            this.previsto = previsto;
            this.intervalId = intervalId;
        }

        boolean disappeared() {
            return previsto && disappearAt != null && !start.isBefore(disappearAt);
        }
    }

    /** Resultado da simulacao: minutos totais + ids das refeicoes recorrentes contadas. */
    private record Sim(int minutes, Set<UUID> countedMealIds) {}

    public static int calculateWaitingTimeForPosition(
            List<Attendance> queue,
            List<ProfessionalInterval> intervals,
            OffsetDateTime now,
            int positionX
    ) {
        return simulate(queue, intervals, now, positionX).minutes();
    }

    /**
     * Quais refeicoes recorrentes (ids) estao no ETA da posicao {@code positionX} — usado para
     * <b>travar (lock) no ingresso</b> (item #37): grava-se este conjunto no atendimento do cliente.
     */
    public static Set<UUID> mealsCountedForPosition(
            List<Attendance> queue,
            List<ProfessionalInterval> intervals,
            OffsetDateTime now,
            int positionX
    ) {
        return simulate(queue, intervals, now, positionX).countedMealIds();
    }

    /**
     * ETA estavel do cliente a partir do lock (item #37). Independe da projecao: soma o restante
     * do atendimento em curso, as duracoes (recalculadas) dos atendimentos a frente e a
     * <b>contribuicao das refeicoes travadas</b> no ingresso. Cada refeicao travada contribui:
     * <ul>
     *   <li>0 se pulada no dia (#41) ou ja encerrada hoje;</li>
     *   <li>o tempo restante se estiver em curso (iniciada hoje);</li>
     *   <li>a duracao cheia se ainda prevista (travada, nao some por ritmo da fila).</li>
     * </ul>
     * Refeicao removida (recorrencia apagada) simplesmente nao esta em {@code lockedMeals}.
     */
    public static int lockedClientWait(
            List<Attendance> ahead,
            int currentRemainingMinutes,
            Collection<ProfessionalInterval> lockedMeals,
            LocalDate today
    ) {
        int total = Math.max(0, currentRemainingMinutes);
        if (ahead != null) {
            // `ahead` e um prefixo da fila de espera ordenada (indices 0..N-1 = posicao real),
            // entao o indice do loop = quantos clientes estao a frente do encaixe AGORA
            // (length_atual), usado para rampar a contribuicao do encaixe (item #38).
            for (int i = 0; i < ahead.size(); i++) {
                total += effectiveEstimatedMinutes(ahead.get(i), i);
            }
        }
        if (lockedMeals != null) {
            for (ProfessionalInterval meal : lockedMeals) {
                total += lockedMealContribution(meal, today);
            }
        }
        return total;
    }

    /**
     * Minutos que um atendimento <b>contribui</b> para a fila enquanto espera (item #38 — encaixe).
     *
     * <p>Para um atendimento <b>NORMAL</b> e o tempo cheio ({@code estimatedTotalMinutes = D}).
     *
     * <p>Para um <b>ENCAIXE</b> ({@code isFit}) o tempo entra <b>rampado</b>: comeca em 0 no
     * ingresso e sobe ate {@code D} conforme a fila anda, em fatias de {@code D / fitAheadLength}
     * a cada cliente que sai da frente. Assim os clientes de tras absorvem o tempo do encaixe sem
     * solavanco (damping do valor exibido) e, quando o encaixe chega na boca da fila, ja contribui
     * o {@code D} inteiro — que e quando o tempo vira real.
     *
     * <pre>
     *   cotas_maduras = fitAheadLength - queueIndex   (0 no ingresso; fitAheadLength na boca da fila)
     *   contribuicao  = D * cotas_maduras / fitAheadLength   (clamp [0, D])
     * </pre>
     *
     * @param a          o atendimento (null -> 0)
     * @param queueIndex posicao 0-based na fila de espera ordenada = clientes a frente AGORA (length_atual)
     */
    public static int effectiveEstimatedMinutes(Attendance a, int queueIndex) {
        if (a == null) {
            return 0;
        }
        int full = a.getEstimatedTotalMinutes();
        if (!a.isFit()) {
            return full;
        }
        Integer aheadLengthBox = a.getFitAheadLength();
        int aheadLength = aheadLengthBox != null ? aheadLengthBox : 0;
        if (aheadLength <= 0) {
            return full; // encaixe sem ninguem a frente: sem rampa, contribui cheio
        }
        int maturedSlices = aheadLength - Math.max(0, queueIndex);
        if (maturedSlices <= 0) {
            return 0; // acabou de ser encaixado: ainda nao maturou nenhuma fatia
        }
        if (maturedSlices >= aheadLength) {
            return full; // ja chegou na boca da fila: tempo cheio (real)
        }
        long ramped = (long) full * maturedSlices / aheadLength;
        return (int) Math.max(0, Math.min(full, ramped));
    }

    // ── Item 40 — Intencao de fila com horario-alvo (agenda sobreposta a fila) ────────────
    //
    // Funcoes PURAS, independentes da persistencia (o modelo de estado/coluna ainda esta em
    // aberto). Recebem o ETA ja calculado em minutos (via calculateWaitingTimeForPosition) e o
    // horario-alvo escolhido pelo cliente. Plugaveis em qualquer representacao (novo
    // AttendanceStatus.INTENT ou flag is_intent + target_time).

    /** Um slot ja reservado na agenda: inicio + duracao. Usado para encaixe cronologico. */
    public record AgendaSlot(OffsetDateTime start, int durationMinutes) {}

    /**
     * Horario de atendimento previsto = agora + ETA da posicao. Conveniencia para as regras abaixo.
     */
    public static OffsetDateTime predictedServeTime(OffsetDateTime now, int etaMinutes) {
        return now.plusMinutes(Math.max(0, etaMinutes));
    }

    /**
     * Trava de viabilidade do alvo (Andre 2026-06): so se reserva uma intencao quando o ETA atual da
     * fila for <b>mais cedo</b> que o alvo. Se a fila ja preve atender em cima ou depois do alvo
     * ({@code previsto >= alvo}), nao ha margem para "segurar a vez" ate la — recusa.
     *
     * @return {@code true} se {@code agora + etaMinutes} for estritamente antes de {@code targetTime}
     */
    public static boolean isTargetFeasible(OffsetDateTime now, int etaMinutes, OffsetDateTime targetTime) {
        if (now == null || targetTime == null) {
            return false;
        }
        return predictedServeTime(now, etaMinutes).isBefore(targetTime);
    }

    /**
     * Gatilho de confirmacao (a intencao vira presenca confirmada e <b>para de ceder posicao</b>):
     * dispara quando o atendimento previsto <b>encosta/cruza</b> o alvo, isto e, quando
     * {@code agora + etaMinutes >= targetTime - tolerancia}. Com {@code toleranceMinutes = 0},
     * confirma exatamente quando o previsto alcanca o alvo.
     */
    public static boolean shouldConfirmIntent(
            OffsetDateTime now, int etaMinutes, OffsetDateTime targetTime, int toleranceMinutes) {
        if (now == null || targetTime == null) {
            return false;
        }
        OffsetDateTime threshold = targetTime.minusMinutes(Math.max(0, toleranceMinutes));
        return !predictedServeTime(now, etaMinutes).isBefore(threshold);
    }

    /**
     * Atribuicao cronologica de slot na agenda (Andre 2026-06): o 1o a pedir um alvo pega o proprio
     * alvo; os seguintes que colidem recebem o <b>proximo horario livre</b> (alvo do conflito +
     * duracao), em cadeia. Retorna o inicio efetivo: o menor instante {@code >= target} que nao
     * sobrepoe nenhum slot ja reservado.
     *
     * <p>Funcao pura: {@code reserved} sao os slots ja confirmados/intencionados (start + duracao);
     * nao muta nada.
     */
    public static OffsetDateTime assignAgendaSlot(
            OffsetDateTime target, int durationMinutes, Collection<AgendaSlot> reserved) {
        OffsetDateTime candidate = target;
        if (reserved == null || reserved.isEmpty() || durationMinutes <= 0) {
            return candidate;
        }
        List<AgendaSlot> sorted = new ArrayList<>(reserved);
        sorted.sort(Comparator.comparing(AgendaSlot::start));
        for (AgendaSlot slot : sorted) {
            OffsetDateTime slotEnd = slot.start().plusMinutes(slot.durationMinutes());
            OffsetDateTime candEnd = candidate.plusMinutes(durationMinutes);
            // sobrepoe se candidate < slotEnd && slot.start < candEnd
            boolean overlaps = candidate.isBefore(slotEnd) && slot.start().isBefore(candEnd);
            if (overlaps) {
                candidate = slotEnd; // empurra para depois do conflito
            }
        }
        return candidate;
    }

    private static int lockedMealContribution(ProfessionalInterval meal, LocalDate today) {
        if (meal == null || meal.isSkippedOn(today) || meal.isDoneOn(today)) {
            return 0; // pulada/removida do dia ou ja consumida hoje
        }
        if (meal.isStartedOn(today)) {
            return (int) meal.getRemainingMinutes(); // em curso: so o que falta
        }
        Integer dur = meal.getDurationMinutes();
        return dur != null ? dur : 0; // prevista: duracao cheia (travada)
    }

    private static Sim simulate(
            List<Attendance> queue,
            List<ProfessionalInterval> intervals,
            OffsetDateTime now,
            int positionX
    ) {
        // positionX == queue.size() e valido: "espera total da fila inteira".
        if (positionX < 0 || positionX > queue.size()) {
            throw new IllegalArgumentException("Invalid position in queue: " + positionX + " (size=" + queue.size() + ")");
        }
        Set<UUID> counted = new HashSet<>();
        if (positionX == 0 || queue.isEmpty()) {
            return new Sim(0, counted);
        }

        List<Block> blocks = buildBlocks(intervals, now);
        return simulateImpl(queue, blocks, now, positionX, counted);
    }

    private static Sim simulateImpl(
            List<Attendance> queue,
            List<Block> blocks,
            OffsetDateTime now,
            int positionX,
            Set<UUID> counted
    ) {
        OffsetDateTime cursor = now;
        long total = 0;

        for (int i = 0; i < positionX; i++) {
            Drain d = drainDue(blocks, cursor, counted);
            cursor = d.cursor();
            total += d.added();

            int duration = queue.get(i).getEstimatedTotalMinutes();
            OffsetDateTime attEnd = cursor.plusMinutes(duration);

            // Intervalos que comecariam DURANTE este atendimento sao empurrados para depois dele.
            for (Block b : blocks) {
                if (b.start.isAfter(cursor) && b.start.isBefore(attEnd)) {
                    b.start = attEnd; // desloca pra frente
                }
            }
            blocks.removeIf(Block::disappeared); // some na proxima

            total += duration;
            cursor = attEnd;
        }

        // Intervalos que ainda venceriam antes de o cliente ser atendido.
        Drain tail = drainDue(blocks, cursor, counted);
        total += tail.added();

        return new Sim((int) total, counted);
    }

    /** Resultado da drenagem de intervalos vencidos: novo cursor + minutos somados. */
    private record Drain(OffsetDateTime cursor, long added) {}

    /** Soma e remove todos os blocos vencidos (start <= cursor), encadeando; registra ids contados. */
    private static Drain drainDue(List<Block> blocks, OffsetDateTime cursor, Set<UUID> counted) {
        long added = 0;
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            blocks.sort(Comparator.comparing(b -> b.start));
            Iterator<Block> it = blocks.iterator();
            while (it.hasNext()) {
                Block b = it.next();
                if (!b.start.isAfter(cursor)) { // start <= cursor
                    added += b.durationMinutes;
                    if (b.intervalId != null) {
                        counted.add(b.intervalId);
                    }
                    cursor = cursor.plusMinutes(b.durationMinutes);
                    it.remove();
                    progressed = true;
                    break;
                }
            }
        }
        return new Drain(cursor, added);
    }

    /** Constroi os blocos efetivos de hoje a partir dos intervalos do profissional. */
    private static List<Block> buildBlocks(List<ProfessionalInterval> intervals, OffsetDateTime now) {
        // BUG2b: o dia e o horario das refeicoes sao do fuso da LOJA (fixedStartTime e relogio
        // de parede) — nao do UTC.
        LocalDate today = BusinessClock.dateAt(now);
        OffsetDateTime endOfDay = BusinessClock.at(today.plusDays(1), LocalTime.MIDNIGHT);

        // Recorrentes previstos (nao iniciados hoje), ordenados pelo horario projetado —
        // para calcular os deadlines de "some na proxima".
        List<ProfessionalInterval> previstos = new ArrayList<>();
        for (ProfessionalInterval iv : intervals) {
            if (iv.getIntervalType() == IntervalType.RECURRENT
                    && iv.getFixedStartTime() != null
                    && !iv.isStartedOn(today)
                    && !iv.isDoneOn(today) // refeicao ja feita hoje nao reaparece como previsto
                    && !iv.isSkippedOn(today)) { // "Nao almocar" do dia (item #41) — fora do ETA
                previstos.add(iv);
            }
        }
        previstos.sort(Comparator.comparing(ProfessionalInterval::getFixedStartTime));

        List<Block> blocks = new ArrayList<>();

        // Iniciados hoje (em andamento) + unexpected de hoje: blocos fixos (nao somem, sem id de lock).
        for (ProfessionalInterval iv : intervals) {
            if (iv.isStartedOn(today)) {
                OffsetDateTime end = iv.getPlannedEndTime();
                if (end != null && end.isAfter(now)) {
                    long remaining = Duration.between(now, end).toMinutes();
                    if (remaining > 0) {
                        blocks.add(new Block(now, (int) remaining, null, false, null));
                    }
                }
            } else if (iv.getIntervalType() == IntervalType.UNEXPECTED
                    && iv.getStartTime() != null
                    && BusinessClock.dateAt(iv.getStartTime()).equals(today)) { // day-scoped: so unexpected de hoje
                OffsetDateTime start = iv.getStartTime();
                OffsetDateTime end = start.plusMinutes(iv.getDurationMinutes());
                if (end.isAfter(now)) {
                    // se ja em andamento, comeca agora com o tempo restante
                    OffsetDateTime effStart = start.isBefore(now) ? now : start;
                    int dur = start.isBefore(now)
                            ? (int) Duration.between(now, end).toMinutes()
                            : iv.getDurationMinutes();
                    if (dur > 0) {
                        blocks.add(new Block(effStart, dur, null, false, null));
                    }
                }
            }
        }

        // Recorrentes previstos: projetados em hoje, deslocados pra frente, com deadline "some na proxima".
        for (int i = 0; i < previstos.size(); i++) {
            ProfessionalInterval iv = previstos.get(i);
            OffsetDateTime projected = BusinessClock.at(today, iv.getFixedStartTime());
            OffsetDateTime start = projected.isBefore(now) ? now : projected; // nunca no passado
            OffsetDateTime disappearAt = (i + 1 < previstos.size())
                    ? BusinessClock.at(today, previstos.get(i + 1).getFixedStartTime())
                    : endOfDay;

            // ja alcancou a proxima refeicao (ou o fim do dia) → some
            if (!start.isBefore(disappearAt)) {
                continue;
            }
            blocks.add(new Block(start, iv.getDurationMinutes(), disappearAt, true, iv.getId()));
        }

        blocks.sort(Comparator.comparing(b -> b.start));
        return blocks;
    }
}
