package com.ospaulos.vupth.domain.payment.service;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry;
import com.ospaulos.vupth.domain.payment.model.AppLedgerEntry.Origin;
import com.ospaulos.vupth.domain.payment.repository.AppLedgerRepository;
import com.ospaulos.vupth.domain.serviceItem.valueObject.PriceSplit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * O livro do app por origem (relatorio-corte-e-multa-por-atendimento, DEC-93). Quando o pagamento de um atendimento e
 * confirmado, grava o que e do corte (SERVICE) e o que e da multa (PENALTY) que foi na mesma cobranca.
 *
 * <p><b>Regra de atribuicao da taxa da cobranca unica</b> (a multa vai junto do corte, uma cobranca so):</p>
 * <ul>
 *   <li><b>Corte:</b> paga a taxa que ja esta no preco dele, a que pagaria sozinho ({@code intermediary_fee} do
 *       atendimento). Lucro do app = a parte do app do preco ({@code platform_fee}).</li>
 *   <li><b>Multa:</b> paga so o acrescimo que ela causa na cobranca: a taxa de pior caso do total menos a do corte
 *       sozinho, nunca negativa. No Pix (taxa fixa) o acrescimo e zero, e a multa e lucro inteiro. Lucro do app = a
 *       multa menos esse acrescimo.</li>
 * </ul>
 * <p>Assim as duas partes somam a taxa de pior caso da cobranca inteira e nenhuma parte paga a taxa fixa duas vezes.
 * Idempotente: um atendimento so entra uma vez (confirmar de novo nao duplica).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AppLedgerService {

    private static final BigDecimal ZERO = new BigDecimal("0.00");

    private final AppLedgerRepository repository;

    public void record(Attendance attendance, BigDecimal penalties, OffsetDateTime at) {
        if (attendance.getId() == null || attendance.getTotalValue().signum() <= 0 || repository.existsByAttendanceId(attendance.getId())) {
            return;
        }
        BigDecimal service = attendance.getTotalValue();
        BigDecimal penalty = penalties == null ? ZERO : penalties;
        BigDecimal serviceFee = attendance.getIntermediaryFee() != null
                ? attendance.getIntermediaryFee() : PriceSplit.intermediaryFor(service);
        repository.save(new AppLedgerEntry(attendance.getId(), Origin.SERVICE, service, serviceFee,
                attendance.getPlatformFee(), at));
        if (penalty.signum() > 0) {
            BigDecimal extra = PriceSplit.intermediaryFor(service.add(penalty)).subtract(serviceFee).max(ZERO);
            repository.save(new AppLedgerEntry(attendance.getId(), Origin.PENALTY, penalty, extra,
                    penalty.subtract(extra), at));
        }
        log.info("[Ledger] attendance={} corte={} multa={}", attendance.getId(), service, penalty);
    }
}
