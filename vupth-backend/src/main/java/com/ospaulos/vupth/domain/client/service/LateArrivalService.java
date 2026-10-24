package com.ospaulos.vupth.domain.client.service;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.client.model.Client;
import com.ospaulos.vupth.domain.client.model.ClientPenaltyEvent;
import com.ospaulos.vupth.domain.client.model.PenaltyType;
import com.ospaulos.vupth.domain.client.repository.ClientPenaltyEventRepository;
import com.ospaulos.vupth.domain.professional.model.Professional;
import com.ospaulos.vupth.domain.professional.model.ProfessionalInterval;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.infrastructure.push.PushNotificationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Contador de atrasos (D4 — Andre 18/09 e 25/09).
 *
 * <ul>
 *   <li><b>Soma entre atendimentos</b> e <b>zera quando a multa nasce</b>: o 3o atraso custa R$ 5,00,
 *       cobrado no proximo atendimento como as outras multas.</li>
 *   <li><b>Tolerancia de 5 min</b> depois do horario comunicado — o mais tarde entre o prometido na
 *       entrada e a chegada da vez (o profissional ficou livre). Marcacao antes disso NAO conta: ninguem
 *       e punido por nao estar la antes do que lhe foi dito, nem pelo atraso em cascata que o sistema
 *       criou quando a fila andou antes. A marcacao em si segue livre (#19 — o cliente cai de posicao):
 *       barra-la quebraria o app do Partner e a fila, que precisam andar.</li>
 *   <li><b>Primeiro atendimento sem multa</b> (DEC-104, 22/10): o cliente sem nenhum atendimento FINALIZADO nao
 *       conta atraso nem falta; o atraso dele nao entra no contador dos 3.</li>
 *   <li><b>Sem aviso nao ha multa</b> (18/09): cada atraso avisa o cliente "este e o seu Nº atraso".</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class LateArrivalService {

    public static final int LATES_PER_PENALTY = 3;
    static final int TOLERANCE_MINUTES = 5;
    static final BigDecimal LATE_PENALTY = new BigDecimal("5.00");

    private final ClientPenaltyEventRepository penaltyEventRepository;
    private final PushNotificationPublisher pushPublisher;
    private final FirstAttendanceExemption firstAttendanceExemption;

    /** falta-do-cliente (DEC-86): a espera antes de o profissional poder marcar que o cliente nao compareceu. */
    @org.springframework.beans.factory.annotation.Value("${vupth.penalty.no-show-wait-minutes:10}")
    private int noShowWaitMinutes = 10;

    /** A marcacao conta para o contador? So depois da tolerancia; sem nada gravado (atendimento antigo), conta. */
    boolean countsAsLate(Attendance attendance, Professional professional, OffsetDateTime now) {
        OffsetDateTime communicated = communicatedTime(attendance, professional, now);
        return communicated == null || !now.isBefore(communicated.plusMinutes(TOLERANCE_MINUTES));
    }

    /**
     * O horario que vale para o atraso: o mais tarde entre o prometido na entrada e a chegada da vez —
     * o ultimo atendimento concluido ou a ultima refeicao encerrada hoje pelo profissional.
     */
    OffsetDateTime communicatedTime(Attendance attendance, Professional professional, OffsetDateTime now) {
        LocalDate today = BusinessClock.dateAt(now);
        Stream<OffsetDateTime> finished = professional.getAttendances().stream()
                .filter(a -> a.getStatus() == AttendanceStatus.FINISHED)
                .map(Attendance::getEndTime)
                .filter(Objects::nonNull)
                .filter(end -> BusinessClock.dateAt(end).equals(today));
        Stream<OffsetDateTime> mealsEnded = professional.getIntervals().stream()
                .filter(iv -> iv.isDoneOn(today))
                .map(ProfessionalInterval::getActualEndTime);
        OffsetDateTime turnArrived = Stream.concat(finished, mealsEnded)
                .filter(Objects::nonNull)
                .filter(t -> !t.isAfter(now))
                .max(OffsetDateTime::compareTo)
                .orElse(null);
        OffsetDateTime promised = attendance.getPromisedTime();
        if (promised == null) {
            return turnArrived;
        }
        return turnArrived == null || promised.isAfter(turnArrived) ? promised : turnArrived;
    }

    /**
     * Conta a marcacao, se ela ja passou da tolerancia; no 3o atraso nasce a multa e o contador zera.
     * Devolve o numero deste atraso (1, 2 ou 3) ou 0 se nao contou.
     */
    public int registerLate(Attendance attendance, Professional professional, OffsetDateTime now) {
        Client client = attendance.getClient();
        if (client == null) {
            return 0; // convidado — sem conta, sem contador
        }
        // sem-multa-no-primeiro-atendimento (DEC-104): quem nunca concluiu um atendimento nao e multado por atraso
        // nem por falta (a falta entra aqui como atraso, DEC-86) e o atraso nao entra no contador dos 3.
        if (firstAttendanceExemption.applies(client.getId())) {
            log.info("[Late] primeiro atendimento — atraso nao conta (client={} attendance={})",
                    client.getId(), attendance.getId());
            return 0;
        }
        if (!countsAsLate(attendance, professional, now)) {
            log.info("[Late] marcacao dentro da tolerancia — nao conta (client={} attendance={})",
                    client.getId(), attendance.getId());
            return 0;
        }
        attendance.setCountedLates(attendance.getCountedLates() + 1);
        int count = client.getLateCount() + 1;
        if (count >= LATES_PER_PENALTY) {
            penaltyEventRepository.save(new ClientPenaltyEvent(
                    client, PenaltyType.LATE_ARRIVAL, attendance.getId(), 0, LATE_PENALTY));
            client.setLateCount(0);
            log.info("[Late] 3o atraso do client={} (attendance={}) -> multa de R$ {}",
                    client.getId(), attendance.getId(), LATE_PENALTY);
        } else {
            client.setLateCount(count);
            log.info("[Late] atraso {} de {} do client={} (attendance={})",
                    count, LATES_PER_PENALTY, client.getId(), attendance.getId());
        }
        notify(client, attendance, count);
        return count;
    }

    /**
     * Desfaz os atrasos CONTADOS deste atendimento (o profissional errou): o contador volta ao que era
     * antes dele. Se eles tinham gerado a multa e ela ainda nao entrou em cobranca, a multa sai.
     */
    public void undoLate(Attendance attendance) {
        Client client = attendance.getClient();
        int counted = attendance.getCountedLates();
        if (client == null || counted == 0) {
            return;
        }
        var penaltyFromThisAttendance = penaltyEventRepository.findByClientIdOrderByCreatedAtDesc(client.getId()).stream()
                .filter(e -> e.getType() == PenaltyType.LATE_ARRIVAL)
                .filter(e -> attendance.getId().equals(e.getAttendanceId()))
                .filter(e -> e.getChargedAttendanceId() == null)
                .findFirst();
        if (penaltyFromThisAttendance.isPresent()) {
            // a multa zerou o contador: antes deste atendimento ele estava em (atual + 3 - contados)
            penaltyEventRepository.delete(penaltyFromThisAttendance.get());
            client.setLateCount(Math.max(0, client.getLateCount() + LATES_PER_PENALTY - counted));
        } else {
            client.setLateCount(Math.max(0, client.getLateCount() - counted));
        }
        attendance.setCountedLates(0);
        log.info("[Late] atrasos desfeitos — client={} lateCount={}", client.getId(), client.getLateCount());
    }

    /** A partir de quando o profissional (ou o dono) pode marcar que o cliente nao compareceu. */
    public OffsetDateTime noShowAvailableAt(Attendance attendance, Professional professional, OffsetDateTime now) {
        OffsetDateTime communicated = communicatedTime(attendance, professional, now);
        OffsetDateTime base = communicated != null ? communicated : attendance.getScheduledTime();
        return base.plusMinutes(noShowWaitMinutes);
    }

    /** A espera (min) depois da hora comunicada para o NAO CHEGOU valer (DEC-101: 10). */
    public int noShowWaitMinutes() {
        return noShowWaitMinutes;
    }

    private void notify(Client client, Attendance attendance, int count) {
        // No 3o ele vai para o fim da fila (Andre, 26/09): pode continuar ali ou sair sem nova multa.
        String body = count >= LATES_PER_PENALTY
                ? "Este é o seu " + count + "º atraso: multa de R$ 5,00 no seu próximo atendimento. Você foi para o fim "
                        + "da fila — pode continuar ou sair sem nova multa."
                : "Este é o seu " + count + "º atraso. No " + LATES_PER_PENALTY + "º, multa de R$ 5,00 e você vai para o fim da fila.";
        pushPublisher.notifyUser(client.getId(), "Você está atrasado", body, Map.of(
                "type", "LATE_MARKED",
                "attendanceId", String.valueOf(attendance.getId()),
                "lateCount", String.valueOf(count),
                "latesUntilPenalty", String.valueOf(Math.max(0, LATES_PER_PENALTY - count)),
                "movedToEnd", String.valueOf(count >= LATES_PER_PENALTY)));
    }
}
