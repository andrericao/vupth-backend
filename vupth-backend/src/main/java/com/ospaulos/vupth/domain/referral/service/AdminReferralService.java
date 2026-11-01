package com.ospaulos.vupth.domain.referral.service;

import com.ospaulos.vupth.api.referral.dto.AdminReferralPaidResponse;
import com.ospaulos.vupth.api.referral.dto.AdminReferralPayoutResponse;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.PayoutPeriod;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.referral.exception.ReferralException;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralPayoutRow;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * O lado do painel da bonificacao por link (BAK-9, fatia 3): o que pagar a cada pessoa, com a data do proximo
 * pagamento pela periodicidade e o dia da campanha (DEC-73), e marcar como pago depois do Pix a mao.
 * So o ADMIN chega aqui ({@code /admin/**}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminReferralService {

    private final ReferralCreditRepository creditRepository;
    private final com.ospaulos.vupth.domain.campaign.repository.CouponCreditRepository couponCreditRepository;
    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;

    /** Repasse automatico (DEC-132): pronto, testado e DESLIGADO. So liga com {@code vupth.asaas.payout-auto=true} e so no sandbox. */
    @org.springframework.beans.factory.annotation.Value("${vupth.asaas.payout-auto:false}")
    private boolean payoutAuto;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ospaulos.vupth.infrastructure.asaas.AsaasClient asaasClient;

    public record AutoPaid(AdminReferralPaidResponse paid, String transferId, java.math.BigDecimal amount) {}

    /**
     * repasse-bonificacao-manual (DEC-132): o repasse automatico, por transferencia Pix do Asaas para a chave do beneficiario, de tudo que
     * esta liberado e a pagar na campanha. Desligado por padrao (409 PAYOUT_AUTO_DISABLED); fora do sandbox tambem nao roda. Depois da
     * transferencia o pago e registrado como no manual ({@link #markPaid}); o manual segue intacto.
     */
    public AutoPaid payAuto(UUID adminId, UUID campaignId, UUID userId) {
        if (!payoutAuto || asaasClient == null) {
            throw new com.ospaulos.vupth.infrastructure.asaas.AsaasException(org.springframework.http.HttpStatus.CONFLICT,
                    "PAYOUT_AUTO_DISABLED", "O repasse automatico esta desligado: pague pelo Pix e marque como pago.");
        }
        AdminReferralPayoutResponse row = payouts().stream()
                .filter(p -> campaignId.equals(p.campaignId()) && userId.equals(p.beneficiaryId())).findFirst()
                .orElseThrow(ReferralException::nothingToPay);
        BaseUser user = userRepository.findById(userId).orElseThrow(ReferralException::nothingToPay);
        if (user.getPixKey() == null || user.getPixKeyType() == null) {
            throw new com.ospaulos.vupth.infrastructure.asaas.AsaasException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "PAYOUT_NO_PIX_KEY", "O beneficiario nao cadastrou a chave Pix.");
        }
        String transferId = asaasClient.transferPix(row.amount(), user.getPixKey(),
                user.getPixKeyType() == com.ospaulos.vupth.domain.payment.PixKeyType.RANDOM ? "EVP" : user.getPixKeyType().name(),
                "VUPTH bonificacao");
        log.info("[Referral] admin={} repasse automatico campaign={} user={} valor={} transferencia={}", adminId, campaignId, userId,
                row.amount(), transferId);
        return new AutoPaid(markPaid(adminId, campaignId, userId), transferId, row.amount());
    }

    /** O que falta pagar, o pagamento mais proximo primeiro. */
    @Transactional(readOnly = true)
    public List<AdminReferralPayoutResponse> payouts() {
        LocalDate today = LocalDate.now(BusinessClock.zone());
        // a bonificacao por link e o desconto dos cupons (cupom-ativado-em-configuracoes) saem no mesmo painel, somados por pessoa e campanha
        java.util.Map<String, ReferralPayoutRow> merged = new java.util.LinkedHashMap<>();
        Stream.concat(creditRepository.findUnpaidPayouts().stream(), couponCreditRepository.findUnpaidPayouts().stream())
                .forEach(row -> merged.merge(row.campaignId() + ":" + row.beneficiaryId(), row,
                        (a, b) -> new ReferralPayoutRow(a.campaignId(), a.beneficiaryId(), a.amount().add(b.amount()),
                                a.credits() + b.credits())));
        return merged.values().stream()
                .map(row -> toResponse(row, today))
                .sorted(Comparator.comparing(AdminReferralPayoutResponse::nextPayoutOn,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AdminReferralPayoutResponse::beneficiaryName,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** Os premios ganhos que esperam a liberacao do Andre, por campanha e pessoa. */
    @Transactional(readOnly = true)
    public List<AdminReferralPayoutResponse> pendingRelease() {
        LocalDate today = LocalDate.now(BusinessClock.zone());
        return creditRepository.findPendingRelease().stream().map(row -> toResponse(row, today)).toList();
    }

    /** O Andre manda: os premios travados da campanha (de uma pessoa, ou de todas) passam a valer no "a pagar". */
    public com.ospaulos.vupth.api.referral.dto.AdminReferralReleasedResponse release(UUID adminId, UUID campaignId, UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        int released = userId == null ? creditRepository.releaseAll(campaignId, now)
                : creditRepository.releaseFor(campaignId, userId, now);
        if (released == 0) {
            throw ReferralException.nothingToRelease();
        }
        log.info("[Referral] admin={} liberou campaign={} user={} creditos={}", adminId, campaignId, userId, released);
        return new com.ospaulos.vupth.api.referral.dto.AdminReferralReleasedResponse(released, now);
    }

    public AdminReferralPaidResponse markPaid(UUID adminId, UUID campaignId, UUID userId) {
        OffsetDateTime now = OffsetDateTime.now();
        int paid = creditRepository.markPaid(campaignId, userId, now) + couponCreditRepository.markPaid(campaignId, userId, now);
        if (paid == 0) {
            throw ReferralException.nothingToPay();
        }
        log.info("[Referral] admin={} marcou pago campaign={} user={} creditos={}", adminId, campaignId, userId, paid);
        return new AdminReferralPaidResponse(paid, now);
    }

    private AdminReferralPayoutResponse toResponse(ReferralPayoutRow row, LocalDate today) {
        Campaign c = campaignRepository.findById(row.campaignId()).orElse(null);
        BaseUser u = userRepository.findById(row.beneficiaryId()).orElse(null);
        PayoutPeriod period = c == null ? null : c.getPayoutPeriod();
        Integer day = c == null ? null : c.getPayoutDay();
        return new AdminReferralPayoutResponse(row.campaignId(), c == null ? null : c.getName(),
                period == null ? null : period.name(), day, nextPayoutOn(period, day, today),
                row.beneficiaryId(), u == null ? null : name(u),
                u == null || u.getPixKeyType() == null ? null : u.getPixKeyType().name(),
                u == null ? null : u.getPixKey(), u == null ? null : u.getPixHolderName(),
                row.amount(), row.credits());
    }

    /** Semanal: o proximo dia da semana (hoje conta). Mensal: o dia do mes, este mes ou o proximo. */
    public static LocalDate nextPayoutOn(PayoutPeriod period, Integer day, LocalDate today) {
        if (period == null || day == null) {
            return null;
        }
        if (period == PayoutPeriod.WEEKLY) {
            return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(day)));
        }
        return today.getDayOfMonth() <= day ? today.withDayOfMonth(day) : today.plusMonths(1).withDayOfMonth(day);
    }

    private static String name(BaseUser u) {
        String joined = Stream.of(u.getFirstName(), u.getLastName())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
        return joined.isEmpty() ? u.getEmail() : joined;
    }
}
