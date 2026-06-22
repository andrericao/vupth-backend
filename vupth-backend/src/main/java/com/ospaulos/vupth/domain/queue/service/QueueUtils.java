package com.ospaulos.vupth.domain.queue.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.interval.valueObject.IntervalType;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Estimativa de espera da fila, considerando os intervalos (refeicoes/pausas) do
 * profissional. Recorrente-aware e null-safe (spec item #11d).
 *
 * Regras dos intervalos na estimativa:
 *  - <b>Iniciado hoje</b> (day-scoped): bloco real em andamento; conta o tempo restante.
 *  - <b>Recorrente previsto</b> (nao iniciado hoje): projetado em hoje@fixedStartTime,
 *    <i>desloca pra frente sempre</i> (nunca no passado; empurrado pelos atendimentos) e
 *    <i>some quando alcanca a proxima refeicao</i> (almoco some no inicio da janta;
 *    janta some no fim do dia). So afeta o numero estimado, nunca o registro.
 *  - <b>Unexpected</b>: pausa avulsa num horario; empurrada para depois do atendimento corrente.
 *
 * Nunca muta a entidade — toda a aritmetica usa projecoes locais ({@link Block}).
 */
public class QueueUtils {

    /** Projecao local de um intervalo na linha do tempo de hoje (nao persiste). */
    private static final class Block {
        OffsetDateTime start;             // mutavel: desloca durante a simulacao
        final int durationMinutes;
        final OffsetDateTime disappearAt; // recorrente previsto: some ao alcancar isto; null = nunca some
        final boolean previsto;           // recorrente nao iniciado (deslocavel/cancelavel)

        Block(OffsetDateTime start, int durationMinutes, OffsetDateTime disappearAt, boolean previsto) {
            this.start = start;
            this.durationMinutes = durationMinutes;
            this.disappearAt = disappearAt;
            this.previsto = previsto;
        }

        boolean disappeared() {
            return previsto && disappearAt != null && !start.isBefore(disappearAt);
        }
    }

    /** Resultado da drenagem de intervalos vencidos: novo cursor + minutos somados. */
    private record Drain(OffsetDateTime cursor, long added) {}

    public static int calculateWaitingTimeForPosition(
            List<Attendance> queue,
            List<ProfessionalInterval> intervals,
            OffsetDateTime now,
            int positionX
    ) {
        // positionX == queue.size() e valido: "espera total da fila inteira".
        if (positionX < 0 || positionX > queue.size()) {
            throw new IllegalArgumentException("Invalid position in queue: " + positionX + " (size=" + queue.size() + ")");
        }
        if (positionX == 0 || queue.isEmpty()) {
            return 0;
        }

        List<Block> blocks = buildBlocks(intervals, now);

        OffsetDateTime cursor = now;
        long total = 0;

        for (int i = 0; i < positionX; i++) {
            // Intervalos "vencidos" (start <= cursor) acontecem antes de servir o proximo.
            Drain d = drainDue(blocks, cursor);
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
        Drain tail = drainDue(blocks, cursor);
        total += tail.added();

        return (int) total;
    }

    /** Soma e remove todos os blocos vencidos (start <= cursor), encadeando. */
    private static Drain drainDue(List<Block> blocks, OffsetDateTime cursor) {
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
        LocalDate today = now.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate();
        OffsetDateTime endOfDay = today.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

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
        previstos.sort(Comparator.comparing(iv -> iv.getFixedStartTime().atDate(today)));

        List<Block> blocks = new ArrayList<>();

        // Iniciados hoje (em andamento) + unexpected de hoje: blocos fixos (nao somem).
        for (ProfessionalInterval iv : intervals) {
            if (iv.isStartedOn(today)) {
                OffsetDateTime end = iv.getPlannedEndTime();
                if (end != null && end.isAfter(now)) {
                    long remaining = Duration.between(now, end).toMinutes();
                    if (remaining > 0) {
                        blocks.add(new Block(now, (int) remaining, null, false));
                    }
                }
            } else if (iv.getIntervalType() == IntervalType.UNEXPECTED
                    && iv.getStartTime() != null
                    && iv.getStartTime().toLocalDate().equals(today)) { // day-scoped: so unexpected de hoje
                OffsetDateTime start = iv.getStartTime();
                OffsetDateTime end = start.plusMinutes(iv.getDurationMinutes());
                if (end.isAfter(now)) {
                    // se ja em andamento, comeca agora com o tempo restante
                    OffsetDateTime effStart = start.isBefore(now) ? now : start;
                    int dur = start.isBefore(now)
                            ? (int) Duration.between(now, end).toMinutes()
                            : iv.getDurationMinutes();
                    if (dur > 0) {
                        blocks.add(new Block(effStart, dur, null, false));
                    }
                }
            }
        }

        // Recorrentes previstos: projetados em hoje, deslocados pra frente, com deadline "some na proxima".
        for (int i = 0; i < previstos.size(); i++) {
            ProfessionalInterval iv = previstos.get(i);
            OffsetDateTime projected = iv.getFixedStartTime().atDate(today);
            OffsetDateTime start = projected.isBefore(now) ? now : projected; // nunca no passado
            OffsetDateTime disappearAt = (i + 1 < previstos.size())
                    ? previstos.get(i + 1).getFixedStartTime().atDate(today)
                    : endOfDay;

            // ja alcancou a proxima refeicao (ou o fim do dia) → some
            if (!start.isBefore(disappearAt)) {
                continue;
            }
            blocks.add(new Block(start, iv.getDurationMinutes(), disappearAt, true));
        }

        blocks.sort(Comparator.comparing(b -> b.start));
        return blocks;
    }
}
