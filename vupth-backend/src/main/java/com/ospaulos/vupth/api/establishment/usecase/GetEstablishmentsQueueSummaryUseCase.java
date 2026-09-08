package com.ospaulos.vupth.api.establishment.usecase;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalIntervalRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.queue.service.QueueUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resumo de fila de VARIAS lojas de uma vez: menor tempo de espera + quantos profissionais com
 * servico cada uma tem. E o que a listagem publica ({@code GET /establishment}) precisa saber de
 * fila — dois numeros por loja.
 *
 * <h2>Por que este use case existe (B10)</h2>
 * A listagem chamava {@link GetEstablishmentWithQueueUseCase} — o use case do <b>detalhe</b> —
 * <b>uma vez por loja</b>, cada uma em transacao propria, para extrair esses dois numeros. O custo
 * nao era de partida: era <b>proporcional as linhas</b>, e as linhas que pagavam nem chegavam na
 * resposta: o filtro de lojas ABERTAS rodava depois, no controller — 98 processadas para devolver
 * 17. (Esse filtro <b>saiu</b> em 06/09: a vitrine devolve tambem as fechadas, porque quem quer
 * AGENDAR precisa acha-las. O custo por loja, que e o assunto aqui, era anterior a ele e
 * independente dele.)
 * Medido antes desta mudanca: <b>~81 ms/loja no Render</b> e <b>~740 ms/loja em producao</b>, que
 * punha a primeira tela do app em ~24s.
 *
 * <p><b>O que muda e a CURVA, nao a constante.</b> Sao <b>quatro consultas no total</b>, qualquer
 * que seja o numero de lojas: profissionais com servico, filas de espera, atendimentos em curso e
 * intervalos. Antes eram, por loja, uma transacao nova + a loja + os profissionais + duas por
 * profissional + os intervalos e as datas puladas em lazy.</p>
 *
 * <h2>O ETA continua sendo o mesmo numero</h2>
 * A estimativa <b>nao</b> virou SQL. Ela e meal-aware (refeicao prevista desloca, some ao alcancar
 * a proxima, encaixe entra rampado) e mora no {@link QueueUtils}; reescrever isso em
 * {@code GROUP BY} criaria uma segunda fonte para o numero que o produto vende. O que saiu do banco
 * foi a <b>ida-e-volta por loja</b>: as linhas vem em quatro consultas e a conta roda em memoria,
 * pela mesma funcao que o snapshot de detalhe usa ({@link QueueUtils#professionalEta}).
 *
 * <h2>Uma loja quebrada nao derruba a lista</h2>
 * Era essa a propriedade do {@code REQUIRES_NEW} do use case de detalhe, e ela <b>continua</b> —
 * por outro mecanismo. Cada loja tem o seu resumo calculado dentro de um {@code try/catch}: falhou,
 * ela sai com {@code (null, 0)} e a lista segue inteira. A diferenca e que agora o {@code catch}
 * protege <b>aritmetica em memoria</b>, e nao acesso ao banco no meio do laco — nenhuma excecao de
 * persistencia por loja pode marcar a transacao da listagem como rollback-only, porque nao ha mais
 * consulta por loja para estourar. Quem mexer aqui precisa preservar as duas coisas: o
 * {@code catch} por loja <b>e</b> o fato de as consultas serem todas antes do laco.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetEstablishmentsQueueSummaryUseCase {

    private final ProfessionalRepository professionalRepository;
    private final AttendanceRepository attendanceRepository;
    private final ProfessionalIntervalRepository intervalRepository;

    /** Menor tempo de espera (null quando a loja nao tem profissional com servico) + a contagem. */
    public record QueueSummary(Integer minWaitMinutes, int availableProfessionals) {
        public static final QueueSummary EMPTY = new QueueSummary(null, 0);
    }

    /** Chave do par profissional+loja — o mesmo alcance da consulta de fila que foi substituida. */
    private record ProfessionalAtEstablishment(UUID establishmentId, UUID professionalId) {}

    @Transactional(readOnly = true)
    public Map<UUID, QueueSummary> execute(Collection<UUID> establishmentIds) {
        if (establishmentIds == null || establishmentIds.isEmpty()) {
            return Map.of();
        }

        // (1) quem entra na conta: profissional afiliado COM ao menos um servico (#31).
        Map<UUID, List<UUID>> professionalsByEstablishment = new LinkedHashMap<>();
        for (Object[] row : professionalRepository.findWithServiceByEstablishmentIds(establishmentIds)) {
            professionalsByEstablishment
                    .computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                    .add((UUID) row[1]);
        }

        List<UUID> professionalIds = professionalsByEstablishment.values().stream()
                .flatMap(List::stream)
                .distinct()
                .toList();

        // (2)(3)(4) fila, corrente e intervalos de TODOS os profissionais da pagina, de uma vez.
        Map<ProfessionalAtEstablishment, List<Attendance>> waitingQueues = waitingQueues(professionalIds);
        Map<UUID, Attendance> currentAttendances = currentAttendances(professionalIds);
        Map<UUID, List<ProfessionalInterval>> intervals = intervals(professionalIds);

        // O mesmo instante para a lista inteira: duas lojas comparadas na tela nao podem ter sido
        // medidas em relogios diferentes (a ordenacao padrao da listagem e por este numero).
        OffsetDateTime now = OffsetDateTime.now();

        Map<UUID, QueueSummary> summaries = new HashMap<>();
        for (UUID establishmentId : establishmentIds) {
            List<UUID> professionals =
                    professionalsByEstablishment.getOrDefault(establishmentId, List.of());
            summaries.put(establishmentId,
                    summaryOf(establishmentId, professionals, waitingQueues, currentAttendances,
                            intervals, now));
        }
        return summaries;
    }

    /** Resumo de UMA loja. O {@code catch} aqui e o que impede uma loja de derrubar a lista. */
    private QueueSummary summaryOf(
            UUID establishmentId,
            List<UUID> professionals,
            Map<ProfessionalAtEstablishment, List<Attendance>> waitingQueues,
            Map<UUID, Attendance> currentAttendances,
            Map<UUID, List<ProfessionalInterval>> intervals,
            OffsetDateTime now
    ) {
        try {
            Integer minWait = null;
            for (UUID professionalId : professionals) {
                int eta = QueueUtils.professionalEta(
                        waitingQueues.getOrDefault(
                                new ProfessionalAtEstablishment(establishmentId, professionalId),
                                List.of()),
                        intervals.getOrDefault(professionalId, List.of()),
                        currentAttendances.get(professionalId),
                        now);
                if (minWait == null || eta < minWait) {
                    minWait = eta;
                }
            }
            return new QueueSummary(minWait, professionals.size());
        } catch (Exception e) {
            log.warn("[Establishment] resumo de fila falhou para {}: {}", establishmentId, e.getMessage());
            return QueueSummary.EMPTY;
        }
    }

    private Map<ProfessionalAtEstablishment, List<Attendance>> waitingQueues(List<UUID> professionalIds) {
        if (professionalIds.isEmpty()) {
            return Map.of();
        }
        Map<ProfessionalAtEstablishment, List<Attendance>> queues = new HashMap<>();
        // As tuplas chegam ordenadas por scheduledTime; o agrupamento preserva essa ordem, e e ela
        // que faz a fila ser fila.
        for (Object[] row : attendanceRepository.findWaitingQueuesByProfessionalIds(professionalIds)) {
            queues.computeIfAbsent(
                            new ProfessionalAtEstablishment((UUID) row[1], (UUID) row[0]),
                            k -> new ArrayList<>())
                    .add((Attendance) row[2]);
        }
        return queues;
    }

    /**
     * Um corrente por profissional: IN_PROGRESS manda, PAUSED so na falta dele — a mesma
     * precedencia do snapshot de detalhe (Partner BUG7: pausado continua exposto ate resume/finish).
     */
    private Map<UUID, Attendance> currentAttendances(List<UUID> professionalIds) {
        if (professionalIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Attendance> current = new HashMap<>();
        for (Object[] row : attendanceRepository.findCurrentAttendancesByProfessionalIds(professionalIds)) {
            UUID professionalId = (UUID) row[0];
            Attendance attendance = (Attendance) row[1];
            Attendance previous = current.get(professionalId);
            if (previous == null || attendance.getStatus() == AttendanceStatus.IN_PROGRESS) {
                current.put(professionalId, attendance);
            }
        }
        return current;
    }

    private Map<UUID, List<ProfessionalInterval>> intervals(List<UUID> professionalIds) {
        if (professionalIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<ProfessionalInterval>> byProfessional = new HashMap<>();
        for (Object[] row : intervalRepository.findByProfessionalIds(professionalIds)) {
            byProfessional.computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                    .add((ProfessionalInterval) row[1]);
        }
        return byProfessional;
    }
}
