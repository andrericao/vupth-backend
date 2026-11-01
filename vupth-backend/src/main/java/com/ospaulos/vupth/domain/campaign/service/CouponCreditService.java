package com.ospaulos.vupth.domain.campaign.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.event.AttendanceFinishedEvent;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CouponCredit;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.campaign.repository.CouponCreditRepository;
import com.ospaulos.vupth.domain.referral.service.AdminReferralService;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O app arca o desconto do cupom (cupom-ativado-em-configuracoes): quando o corte termina, o desconto vira credito do
 * profissional, que recebe o valor cheio — o cliente pagou menos. O credito entra no painel de pagamentos da campanha.
 * Corte de teste (DEC-106) nao gera credito: nada e pago de verdade.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponCreditService {

    private final CouponCreditRepository creditRepository;
    private final AttendanceRepository attendanceRepository;
    private final CampaignRepository campaignRepository;

    public record Item(
            @JsonProperty("attendance_id") UUID attendanceId,
            @JsonProperty("campaign") String campaign,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("created_at") OffsetDateTime createdAt,
            @JsonProperty("paid_at") OffsetDateTime paidAt) {
    }

    public record Credits(
            @JsonProperty("open_amount") BigDecimal openAmount,
            @JsonProperty("next_payout") LocalDate nextPayout,
            @JsonProperty("items") List<Item> items) {
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFinished(AttendanceFinishedEvent event) {
        try {
            credit(event.attendanceId());
        } catch (Exception e) {
            log.error("[Coupon] credito falhou — attendance={}: {}", event.attendanceId(), e.getMessage(), e);
        }
    }

    /** Cria o credito do desconto do corte concluido, uma vez so. */
    public void credit(UUID attendanceId) {
        Attendance a = attendanceRepository.findByIdEager(attendanceId).orElse(null);
        if (a == null || a.getStatus() != AttendanceStatus.FINISHED || a.getCouponCampaignId() == null
                || a.getDiscount() == null || a.getDiscount().signum() <= 0 || a.isTest()
                || creditRepository.existsByAttendanceId(attendanceId)) {
            return;
        }
        BigDecimal amount = repass(a);
        if (amount.signum() <= 0) {
            return; // a tarifa do app absorve o desconto: nada a repassar
        }
        creditRepository.save(new CouponCredit(a.getCouponCampaignId(), a.getId(), a.getProfessional().getId(),
                amount, OffsetDateTime.now()));
        log.info("[Coupon] credito — campaign={} attendance={} para={} R$ {}",
                a.getCouponCampaignId(), a.getId(), a.getProfessional().getId(), amount);
    }

    /**
     * DEC-127: o app repassa a diferenca entre o que o profissional estipulou e o que o cliente paga com desconto
     * (R$ 30,00 − R$ 22,90 = R$ 7,10), nao o desconto inteiro: os R$ 2,90 de tarifa e margem sao do app. Servico legado
     * (sem divisao): o desconto inteiro, como antes.
     */
    static BigDecimal repass(Attendance a) {
        if (a.getProReceives() == null) {
            return a.getDiscount();
        }
        // pelo Asaas o profissional recebe o split (ja sem a tarifa, que o app paga): o app repassa o resto (decisao do Andre, 29/10)
        BigDecimal received = a.isViaGateway() ? a.getAsaasSplitAmount() : a.getAmountDue();
        return a.getProReceives().subtract(received).max(BigDecimal.ZERO);
    }

    /** Ao profissional: o que o app deve a ele pelos descontos e quando e o proximo pagamento. */
    @Transactional(readOnly = true)
    public Credits creditsOf(UUID userId) {
        List<CouponCredit> credits = creditRepository.findByBeneficiaryIdOrderByCreatedAtDesc(userId);
        Map<UUID, Campaign> campaigns = campaignRepository.findAllById(
                        credits.stream().map(CouponCredit::getCampaignId).distinct().toList())
                .stream().collect(Collectors.toMap(Campaign::getId, Function.identity()));
        LocalDate today = LocalDate.now(BusinessClock.zone());
        BigDecimal open = credits.stream().filter(c -> c.getPaidAt() == null)
                .map(CouponCredit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        LocalDate next = credits.stream().filter(c -> c.getPaidAt() == null)
                .map(c -> campaigns.get(c.getCampaignId()))
                .filter(Objects::nonNull)
                .map(c -> AdminReferralService.nextPayoutOn(c.getPayoutPeriod(), c.getPayoutDay(), today))
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo).orElse(null);
        List<Item> items = credits.stream()
                .map(c -> new Item(c.getAttendanceId(),
                        campaigns.containsKey(c.getCampaignId()) ? campaigns.get(c.getCampaignId()).getName() : null,
                        c.getAmount(), c.getCreatedAt(), c.getPaidAt()))
                .toList();
        return new Credits(open, next, items);
    }
}
