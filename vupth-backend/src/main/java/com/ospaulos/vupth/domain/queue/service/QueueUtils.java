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
        final OffsetDateTime disappearAt; // recorrente previsto: some ao passar do inicio maximo (Modelo B); null = nunca some
        final boolean previsto;           // recorrente nao iniciado (deslocavel/cancelavel)
        final UUID intervalId;            // id da refeicao a travar no ingresso (prevista ou em curso); null p/ unexpected

        Block(OffsetDateTime start, int durationMinutes, OffsetDateTime disappearAt,
              boolean previsto, UUID intervalId) {
            this.start = start;
            this.durationMinutes = durationMinutes;
            this.disappearAt = disappearAt;
            this.previsto = previsto;
            this.intervalId = intervalId;
        }

        boolean disappeared() {
            return previsto && disappearAt != null && start.isAfter(disappearAt);
        }
    }

    /** Resultado da simulacao: minutos totais + ids das refeicoes recorrentes contadas. */
    private record Sim(int minutes, Set<UUID> countedMealIds) {}

    /**
     * <b>Tempo estimado exibido de um profissional</b> = espera da fila inteira (meal-aware) +
     * o que resta do atendimento em curso.
     *
     * <p><b>Fonte comum do numero, de proposito.</b> Duas telas mostram este mesmo tempo: o
     * snapshot de DETALHE ({@code professionals-with-queue}, via
     * {@code ProfessionalWithQueueResponse}) e o resumo da LISTAGEM ({@code GET /establishment},
     * via {@code GetEstablishmentsQueueSummaryUseCase}). Enquanto a listagem chamava o use case de
     * detalhe 1x por loja, os dois liam a mesma conta por construcao — e era esse acoplamento que
     * custava a ida-e-volta ao banco por loja (B10). Ao separar o caminho da listagem, a conta
     * precisou virar funcao unica: se cada lado somasse por conta propria, a mesma loja mostraria
     * <b>dois tempos diferentes em duas telas</b>, e o tempo estimado e o produto.</p>
     *
     * @param waiting   fila de espera ordenada por {@code scheduledTime} (SCHEDULED + LATE)
     * @param intervals intervalos do profissional (refeicoes previstas/iniciadas e pausas)
     * @param current   atendimento em curso (IN_PROGRESS ou, na falta dele, PAUSED); pode ser null
     * @param now       instante de referencia — o mesmo para a fila e para o restante do corrente
     */
    public static int professionalEta(
            List<Attendance> waiting,
            List<ProfessionalInterval> intervals,
            Attendance current,
            OffsetDateTime now
    ) {
        List<Attendance> queue = waiting != null ? waiting : List.of();
        List<ProfessionalInterval> meals = intervals != null ? intervals : List.of();
        int eta = calculateWaitingTimeForPosition(queue, meals, now, queue.size());
        if (current != null) {
            eta += current.getRemainingMinutes(now);
        }
        return eta;
    }

    /**
     * Folga da espera EXIBIDA (Partner S10, regra global de 23/07): o cliente ve 10 min a menos, com
     * piso 0, para chegar antes. So a exibicao — as regras (multa de saida, tolerancia, horario
     * prometido do atraso) seguem o tempo real (Andre, 26/09): ninguem e multado nem marcado atrasado
     * por causa da folga. Aplicar SO onde o numero vai para a tela.
     */
    public static final int DISPLAY_BUFFER_MINUTES = 10;

    /** A espera como o cliente a ve: o tempo real menos a folga, com piso 0. */
    public static int displayedWait(int realMinutes) {
        return Math.max(0, realMinutes - DISPLAY_BUFFER_MINUTES);
    }

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
        // sem a lista inteira nem o relogio, a supressao (Modelo B) olha so as travadas, no inicio do dia
        return lockedClientWait(ahead, currentRemainingMinutes, lockedMeals, lockedMeals,
                BusinessClock.at(today, LocalTime.MIDNIGHT));
    }

    /**
     * {@link #lockedClientWait(List, int, Collection, LocalDate)} com a supressao do Modelo B: a
     * refeicao travada que passou do inicio maximo sem iniciar deixa de contar. {@code allIntervals}
     * sao todas as refeicoes do profissional (o inicio maximo depende da proxima).
     */
    public static int lockedClientWait(
            List<Attendance> ahead,
            int currentRemainingMinutes,
            Collection<ProfessionalInterval> lockedMeals,
            Collection<ProfessionalInterval> allIntervals,
            OffsetDateTime now
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
                total += lockedMealContribution(meal, allIntervals, now);
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

    /**
     * Horario-alvo que cai numa refeicao/pausa do profissional e EMPURRADO para o fim dela (Client 03/07,
     * regra do Andre: "se ele agendar 12:30, e avisado que sera atendido apos o intervalo"). Nao recusa:
     * so reposiciona. Olha o dia do proprio {@code start}: refeicao em curso, recorrente prevista (fora
     * as puladas #41 e as ja feitas) e pausa avulsa. Encadeia: se o fim de uma cai em outra, segue.
     *
     * @return o inicio que nao sobrepoe nenhum intervalo — o proprio {@code start} se nada colide
     */
    public static OffsetDateTime pushPastBreaks(OffsetDateTime start, int durationMinutes,
                                                Collection<ProfessionalInterval> intervals) {
        if (start == null || intervals == null || intervals.isEmpty()) {
            return start;
        }
        LocalDate day = BusinessClock.dateAt(start);
        List<OffsetDateTime[]> windows = new ArrayList<>();
        for (ProfessionalInterval iv : intervals) {
            if (iv == null || iv.isSkippedOn(day) || iv.isDoneOn(day)) {
                continue;
            }
            if (iv.isStartedOn(day) && iv.getActualStartTime() != null && iv.getPlannedEndTime() != null) {
                windows.add(new OffsetDateTime[]{iv.getActualStartTime(), iv.getPlannedEndTime()});
            } else if (iv.getIntervalType() == IntervalType.RECURRENT && iv.getFixedStartTime() != null
                    && iv.getDurationMinutes() != null) {
                OffsetDateTime from = BusinessClock.at(day, iv.getFixedStartTime());
                windows.add(new OffsetDateTime[]{from, from.plusMinutes(iv.getDurationMinutes())});
            } else if (iv.getIntervalType() == IntervalType.UNEXPECTED && iv.getStartTime() != null
                    && iv.getDurationMinutes() != null && BusinessClock.dateAt(iv.getStartTime()).equals(day)) {
                windows.add(new OffsetDateTime[]{iv.getStartTime(), iv.getStartTime().plusMinutes(iv.getDurationMinutes())});
            }
        }
        OffsetDateTime candidate = start;
        boolean moved = true;
        while (moved) {
            moved = false;
            OffsetDateTime end = candidate.plusMinutes(Math.max(0, durationMinutes));
            for (OffsetDateTime[] w : windows) {
                if (candidate.isBefore(w[1]) && w[0].isBefore(end)) {
                    candidate = w[1];
                    moved = true;
                    break;
                }
            }
        }
        return candidate;
    }

    private static int lockedMealContribution(ProfessionalInterval meal, Collection<ProfessionalInterval> all,
                                              OffsetDateTime now) {
        LocalDate today = BusinessClock.dateAt(now);
        if (meal == null || meal.isSkippedOn(today) || meal.isDoneOn(today)) {
            return 0; // pulada/removida do dia ou ja consumida hoje
        }
        if (isSuppressed(meal, all, now)) {
            return 0; // Modelo B: passou do inicio maximo sem iniciar — sumiu do dia, mesmo travada
        }
        if (meal.isStartedOn(today)) {
            return (int) meal.getRemainingMinutes(); // em curso: so o que falta
        }
        Integer dur = meal.getDurationMinutes();
        return dur != null ? dur : 0; // prevista: duracao cheia (travada)
    }

    // ── Refeicoes Modelo B — janela flexivel (Partner S3, spec de 28/06) ─────────────────────────

    /** Folga minima entre uma refeicao e a proxima: o almoco nao comeca a menos de 3h da janta. */
    static final int MIN_GAP_TO_NEXT_MEAL_MINUTES = 180;

    /**
     * Inicio maximo da refeicao no dia (Modelo B): o menor entre (a proxima refeicao - 3h) e (o
     * fechamento do dia - a duracao) — passou dele sem iniciar, ela some do dia. Nunca antes do proprio
     * horario-alvo (refeicoes cadastradas a menos de 3h uma da outra nao ficam sempre suprimidas). Sem
     * proxima refeicao nem fechamento conhecido, vale o fim do dia.
     */
    public static OffsetDateTime latestStart(ProfessionalInterval meal, Collection<ProfessionalInterval> all,
                                             LocalDate day) {
        OffsetDateTime target = BusinessClock.at(day, meal.getFixedStartTime());
        OffsetDateTime latest = BusinessClock.at(day.plusDays(1), LocalTime.MIDNIGHT);
        if (all != null) {
            for (ProfessionalInterval other : all) {
                if (other != null && other != meal && other.getIntervalType() == IntervalType.RECURRENT
                        && other.getFixedStartTime() != null
                        && other.getFixedStartTime().isAfter(meal.getFixedStartTime())
                        && !other.isSkippedOn(day)) {
                    OffsetDateTime bound = BusinessClock.at(day, other.getFixedStartTime())
                            .minusMinutes(MIN_GAP_TO_NEXT_MEAL_MINUTES);
                    if (bound.isBefore(latest)) {
                        latest = bound;
                    }
                }
            }
        }
        LocalTime closes = closingOf(meal, day);
        if (closes != null && meal.getDurationMinutes() != null) {
            OffsetDateTime bound = BusinessClock.at(day, closes).minusMinutes(meal.getDurationMinutes());
            if (bound.isBefore(latest)) {
                latest = bound;
            }
        }
        return latest.isBefore(target) ? target : latest;
    }

    /**
     * A refeicao recorrente passou do inicio maximo hoje sem ser iniciada, feita ou pulada — sumiu do
     * dia (Modelo B). "Iniciar refeicao" pula as suprimidas e comeca a proxima.
     */
    public static boolean isSuppressed(ProfessionalInterval meal, Collection<ProfessionalInterval> all,
                                       OffsetDateTime now) {
        if (meal == null || meal.getIntervalType() != IntervalType.RECURRENT || meal.getFixedStartTime() == null) {
            return false;
        }
        LocalDate day = BusinessClock.dateAt(now);
        if (meal.isStartedOn(day) || meal.isDoneOn(day) || meal.isSkippedOn(day)) {
            return false;
        }
        return now.isAfter(latestStart(meal, all, day));
    }

    /** Fechamento do dia da loja do profissional; nulo se desconhecido (sem loja, dia fechado, fora de sessao). */
    private static LocalTime closingOf(ProfessionalInterval meal, LocalDate day) {
        try {
            if (meal.getProfessional() == null || meal.getProfessional().getEstablishment() == null) {
                return null;
            }
            var hours = meal.getProfessional().getEstablishment().getBusinessHours().on(day);
            return hours == null || hours.closed() ? null : hours.closes();
        } catch (RuntimeException e) {
            return null; // colecao lazy fora de sessao: sem fechamento, vale a regra da proxima refeicao
        }
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
        List<Block> blocks = buildBlocks(intervals, now);
        if (positionX == 0) {
            // Ninguem a frente, mas a refeicao/pausa EM CURSO ainda esta entre o cliente e a cadeira:
            // devolver 0 aqui era o "agora" com o profissional almocando (Partner/Client, 17-25/09).
            // So o que ja esta acontecendo conta — refeicao prevista nao entra sem fila que a alcance.
            blocks.removeIf(b -> b.previsto || b.start.isAfter(now));
            return new Sim((int) drainDue(blocks, now, counted).added(), counted);
        }

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

        // Iniciados hoje (em andamento) + unexpected de hoje: blocos fixos (nao somem). O iniciado leva
        // o id para ser TRAVADO no ingresso de quem chega durante ele (lockedMealContribution conta o
        // restante); sem o id, o wait-time de quem entrou no meio da refeicao a esquecia.
        for (ProfessionalInterval iv : intervals) {
            if (iv.isStartedOn(today)) {
                OffsetDateTime end = iv.getPlannedEndTime();
                if (end != null && end.isAfter(now)) {
                    long remaining = Duration.between(now, end).toMinutes();
                    if (remaining > 0) {
                        blocks.add(new Block(now, (int) remaining, null, false, iv.getId()));
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

        // Recorrentes previstos: projetados em hoje, deslocados pra frente, e suprimidos ao passar do
        // inicio maximo da janela (Modelo B).
        for (int i = 0; i < previstos.size(); i++) {
            ProfessionalInterval iv = previstos.get(i);
            OffsetDateTime projected = BusinessClock.at(today, iv.getFixedStartTime());
            OffsetDateTime start = projected.isBefore(now) ? now : projected; // nunca no passado
            OffsetDateTime disappearAt = latestStart(iv, intervals, today);

            // Modelo B: empurrada para depois do inicio maximo -> suprimida no dia (sai do ETA)
            if (start.isAfter(disappearAt)) {
                continue;
            }
            blocks.add(new Block(start, iv.getDurationMinutes(), disappearAt, true, iv.getId()));
        }

        blocks.sort(Comparator.comparing(b -> b.start));
        return blocks;
    }
}
