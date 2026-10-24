package com.ospaulos.vupth.domain.debt.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.debt.model.DebtOrigin;
import com.ospaulos.vupth.domain.debt.model.DebtStatus;
import com.ospaulos.vupth.domain.debt.model.ProfessionalDebt;
import com.ospaulos.vupth.domain.debt.repository.ProfessionalDebtRepository;
import com.ospaulos.vupth.domain.payment.PaymentMethod;
import com.ospaulos.vupth.domain.setting.AppSettingService;
import com.ospaulos.vupth.infrastructure.config.AppPix;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Pagamento por fora (divida-por-fora-e-abatimento, DEC-109/115). O Recebi por fora do app (cartao fisico, dinheiro, nota) deixa o
 * profissional devendo ao app o preco menos o que ele recebe — os R$ 2,90 inteiros do corte de R$ 32,90, ja com a taxa do gateway
 * e a sobra. So atendimento real; o de teste nunca gera. Sem o Asaas implantado ninguem deve de verdade: a divida nasce
 * {@code simulated} (so teste) ate o Painel ligar a flag {@link AppSettingService#DEBT_REAL}. O limite de R$ 25 de divida real
 * acumulada so avisa e mostra o Pix de cobranca do app; o abatimento automatico (no maximo 50% do que o profissional recebe por
 * corte) so existe com o pagamento passando pelo app, e fica para o gateway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProfessionalDebtService {

    /** Quanto de abatimento por corte, no maximo, da parte do profissional (DEC-115), para quando houver gateway. */
    public static final BigDecimal MAX_ABATEMENT_SHARE = new BigDecimal("0.50");

    private final ProfessionalDebtRepository repository;
    private final AppSettingService settings;
    private final AppPix appPix;

    @Value("${vupth.debt.limit:25.00}")
    private BigDecimal limit = new BigDecimal("25.00");

    public record Item(
            @JsonProperty("attendance_id") UUID attendanceId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("origin") DebtOrigin origin,
            @JsonProperty("status") DebtStatus status,
            @JsonProperty("simulated") boolean simulated,
            @JsonProperty("created_at") OffsetDateTime createdAt) {
    }

    /** O que o profissional ve: nada deve enquanto a divida nao for real; o simulado aparece a parte, como teste. */
    public record Debt(
            @JsonProperty("real") boolean real,
            @JsonProperty("open_amount") BigDecimal openAmount,
            @JsonProperty("simulated_amount") BigDecimal simulatedAmount,
            @JsonProperty("limit") BigDecimal limit,
            @JsonProperty("over_limit") boolean overLimit,
            @JsonProperty("pix_copy_paste") String pixCopyPaste,
            @JsonProperty("holder_name") String holderName,
            @JsonProperty("items") List<Item> items) {
    }

    public record Row(
            @JsonProperty("professional_id") UUID professionalId,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("simulated") boolean simulated) {
    }

    public boolean isReal() {
        return settings.flag(AppSettingService.DEBT_REAL, false);
    }

    /** No Recebi: grava a divida do corte pago por fora. Pix, teste, servico legado (sem divisao) e corte ja registrado nao geram. */
    public void recordOnReceived(Attendance attendance, PaymentMethod method) {
        if (attendance.isTest() || method == null || method == PaymentMethod.PIX
                || attendance.getProReceives() == null || attendance.getProfessional() == null
                || repository.existsByAttendanceId(attendance.getId())) {
            return;
        }
        BigDecimal amount = attendance.getTotalValue().subtract(attendance.getProReceives());
        if (amount.signum() <= 0) {
            return;
        }
        boolean simulated = !isReal();
        repository.save(new ProfessionalDebt(attendance.getProfessional().getId(), attendance.getId(), amount,
                DebtOrigin.PAID_OUTSIDE, simulated, OffsetDateTime.now()));
        log.info("[Debt] corte por fora — professional={} attendance={} R$ {} simulada={}",
                attendance.getProfessional().getId(), attendance.getId(), amount, simulated);
    }

    @Transactional(readOnly = true)
    public Debt debtOf(UUID professionalId) {
        BigDecimal open = repository.sumOpen(professionalId, false);
        BigDecimal simulated = repository.sumOpen(professionalId, true);
        boolean over = open.compareTo(limit) >= 0 && open.signum() > 0;
        String pix = open.signum() > 0
                ? appPix.copyPaste(open, "VUPTHDIVIDA" + professionalId.toString().replace("-", "")) : null;
        List<Item> items = repository.findByProfessionalIdOrderByCreatedAtDesc(professionalId).stream()
                .map(d -> new Item(d.getAttendanceId(), d.getAmount(), d.getOrigin(), d.getStatus(), d.isSimulated(),
                        d.getCreatedAt()))
                .toList();
        return new Debt(isReal(), open, simulated, limit, over, pix, pix != null && appPix.configured() ? appPix.holderName() : null,
                items);
    }

    /** O Painel (ou o ADMIN, depois do Pix ao app): quita a divida real em aberto do profissional. Devolve quanto quitou. */
    public BigDecimal settle(UUID professionalId) {
        OffsetDateTime now = OffsetDateTime.now();
        List<ProfessionalDebt> open = repository.findByProfessionalIdAndStatusAndSimulated(professionalId, DebtStatus.OPEN, false);
        BigDecimal total = BigDecimal.ZERO;
        for (ProfessionalDebt d : open) {
            d.setStatus(DebtStatus.SETTLED);
            d.setSettledAt(now);
            total = total.add(d.getAmount());
        }
        repository.saveAll(open);
        log.info("[Debt] quitada — professional={} R$ {} ({} cortes)", professionalId, total, open.size());
        return total;
    }

    /** Dividas em aberto por profissional, para o Painel; as reais primeiro. */
    @Transactional(readOnly = true)
    public List<Row> openRows() {
        java.util.ArrayList<Row> rows = new java.util.ArrayList<>();
        repository.openByProfessional(false).forEach(r -> rows.add(new Row((UUID) r[0], (BigDecimal) r[1], false)));
        repository.openByProfessional(true).forEach(r -> rows.add(new Row((UUID) r[0], (BigDecimal) r[1], true)));
        return rows;
    }

    /** O abatimento de um corte pago pelo app (gateway): no maximo 50% do que o profissional recebe, e nao mais que a divida. */
    public static BigDecimal abatement(BigDecimal proReceives, BigDecimal openDebt) {
        BigDecimal cap = proReceives.multiply(MAX_ABATEMENT_SHARE).setScale(2, java.math.RoundingMode.DOWN);
        return openDebt.min(cap).max(BigDecimal.ZERO);
    }
}
