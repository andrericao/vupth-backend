package com.ospaulos.vupth.domain.referral.service;

import com.ospaulos.vupth.api.referral.dto.ProfessionalPaymentResponse;
import com.ospaulos.vupth.api.referral.dto.ProfessionalPaymentSummaryRow;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.campaign.repository.CouponCreditRepository;
import com.ospaulos.vupth.domain.referral.exception.ReferralException;
import com.ospaulos.vupth.domain.referral.model.ProfessionalPayment;
import com.ospaulos.vupth.domain.referral.repository.ProfessionalPaymentRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * pagamento-ao-profissional (Andre, 06/11): o historico imutavel do que o app pagou de premios e creditos de campanha, o estorno e o a
 * pagar x pago. O que e receita da VUPTH (2%, arredondamento, multas) nao passa aqui. O pagamento nasce em
 * {@link AdminReferralService#markPaid}, que cobre tudo o que esta a pagar da pessoa na campanha.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfessionalPaymentService {

    private final ProfessionalPaymentRepository paymentRepository;
    private final ReferralCreditRepository creditRepository;
    private final CouponCreditRepository couponCreditRepository;
    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbc;

    /** O estorno e uma linha nova; os premios daquele pagamento voltam ao "a pagar". Um pagamento se estorna uma vez so. */
    @Transactional
    public ProfessionalPaymentResponse reverse(UUID adminId, UUID paymentId, String reason) {
        ProfessionalPayment payment = paymentRepository.findById(paymentId).orElseThrow(ReferralException::paymentNotFound);
        if (payment.getType() != ProfessionalPayment.Type.PAYMENT || paymentRepository.existsByReversalOf(paymentId)) {
            throw ReferralException.alreadyReversed();
        }
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        ProfessionalPayment reversal = paymentRepository.save(payment.reversal(reason, adminId, now));
        int credits = creditRepository.reopen(payment.getCampaignId(), payment.getProfessionalId(), payment.getPaidAt())
                + couponCreditRepository.reopen(payment.getCampaignId(), payment.getProfessionalId(), payment.getPaidAt());
        log.info("[Payment] admin={} estornou pagamento={} premios={}", adminId, paymentId, credits);
        return toResponses(List.of(reversal)).get(0);
    }

    /** O historico, do mais novo ao mais antigo; o periodo vale para a data do pagamento. */
    @Transactional(readOnly = true)
    public List<ProfessionalPaymentResponse> history(UUID professionalId, OffsetDateTime from, OffsetDateTime to) {
        List<ProfessionalPayment> rows = professionalId == null
                ? paymentRepository.findAll(org.springframework.data.domain.Sort.by("paidAt").descending())
                : paymentRepository.findByProfessionalIdOrderByPaidAtDesc(professionalId);
        return toResponses(rows.stream()
                .filter(p -> from == null || !p.getPaidAt().isBefore(from))
                .filter(p -> to == null || p.getPaidAt().isBefore(to))
                .toList());
    }

    /** A pagar agora x pago no periodo, por pessoa; quem tem mais a receber vem primeiro. */
    @Transactional(readOnly = true)
    public List<ProfessionalPaymentSummaryRow> summary(OffsetDateTime from, OffsetDateTime to) {
        List<Object> args = new ArrayList<>();
        StringBuilder period = new StringBuilder();
        if (from != null) {
            period.append(" AND paid_at >= ?");
            args.add(Timestamp.from(from.toInstant()));
        }
        if (to != null) {
            period.append(" AND paid_at < ?");
            args.add(Timestamp.from(to.toInstant()));
        }
        return jdbc.query("""
                WITH open AS (
                  SELECT beneficiary_id AS id, sum(amount) AS amount FROM (
                    SELECT beneficiary_id, amount FROM referral_credit
                     WHERE paid_at IS NULL AND released_at IS NOT NULL AND NOT test
                    UNION ALL
                    SELECT beneficiary_id, amount FROM coupon_credit WHERE paid_at IS NULL) x
                  GROUP BY beneficiary_id
                ), done AS (
                  SELECT professional_id AS id,
                         sum(CASE WHEN type = 'PAYMENT' THEN amount ELSE -amount END) AS amount,
                         max(paid_at) FILTER (WHERE type = 'PAYMENT') AS last_paid_at
                    FROM professional_payment WHERE TRUE""" + period + """

                  GROUP BY professional_id
                )
                SELECT coalesce(o.id, d.id) AS id, trim(coalesce(u.first_name,'') || ' ' || coalesce(u.last_name,'')) AS name,
                       coalesce(o.amount,0) AS to_pay, coalesce(d.amount,0) AS paid, d.last_paid_at
                  FROM open o FULL JOIN done d ON d.id = o.id
                  LEFT JOIN base_user u ON u.id = coalesce(o.id, d.id)
                 ORDER BY to_pay DESC, name
                """, (rs, i) -> new ProfessionalPaymentSummaryRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getBigDecimal("to_pay"), rs.getBigDecimal("paid"), rs.getObject("last_paid_at", OffsetDateTime.class)), args.toArray());
    }

    private List<ProfessionalPaymentResponse> toResponses(List<ProfessionalPayment> rows) {
        Map<UUID, Campaign> campaigns = campaignRepository.findAllById(rows.stream().map(ProfessionalPayment::getCampaignId).distinct().toList())
                .stream().collect(Collectors.toMap(Campaign::getId, Function.identity()));
        Map<UUID, BaseUser> users = new java.util.HashMap<>();
        rows.stream().map(ProfessionalPayment::getProfessionalId).distinct()
                .forEach(id -> userRepository.findById(id).ifPresent(u -> users.put(id, u)));
        return rows.stream().map(p -> {
            Campaign campaign = campaigns.get(p.getCampaignId());
            BaseUser user = users.get(p.getProfessionalId());
            String name = user == null ? null
                    : ((user.getFirstName() == null ? "" : user.getFirstName()) + " " + (user.getLastName() == null ? "" : user.getLastName())).trim();
            return new ProfessionalPaymentResponse(p.getId(), p.getProfessionalId(), name, p.getCampaignId(),
                    campaign == null ? null : campaign.getName(), p.getType().name(), p.getAmount(), p.getPaidAt(), p.getNote(),
                    p.getReversalOf(), paymentRepository.existsByReversalOf(p.getId()));
        }).toList();
    }
}
