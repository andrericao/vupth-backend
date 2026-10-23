package com.ospaulos.vupth.domain.campaign.service;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.BenefitBase;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignAudience;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Usar um cupom ativado no corte (cupom-ativado-em-configuracoes, DEC-110/112/115). O cupom so vale se estiver na lista de ativos
 * de quem pede: ativado por ele, de desconto, dentro da validade (o fim da campanha), no dia da semana da campanha e ainda
 * nao usado; um so por corte. Fora disso, 422 {@code COUPON_NOT_ACTIVE} (o aviso padronizado para o Client). O desconto e
 * o menor entre o valor do cupom e a base da campanha, nunca acima do corte; o app arca (ver {@link CouponCreditService}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponService {

    /** Cortes que nao valeram devolvem o cupom a quem o ativou. */
    private static final List<AttendanceStatus> RETURNS_COUPON = List.of(AttendanceStatus.CANCELLED, AttendanceStatus.REMOVED);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CampaignRepository campaignRepository;
    private final CampaignRedemptionRepository redemptionRepository;
    private final AttendanceRepository attendanceRepository;

    /** Aplica o cupom ao atendimento recem-criado (preco cheio ja congelado). Nulo = sem cupom. */
    public void apply(Attendance attendance, UUID couponCampaignId) {
        if (couponCampaignId == null || attendance.getClient() == null) {
            return;
        }
        UUID clientId = attendance.getClient().getId();
        Campaign campaign = campaignRepository.findById(couponCampaignId).orElseThrow(CampaignException::couponNotActive);
        OffsetDateTime now = OffsetDateTime.now();
        boolean valid = campaign.getType() == CampaignType.DISCOUNT
                && campaign.isRunning(now)
                && (campaign.getAudience() == null || campaign.getAudience() == CampaignAudience.CLIENT)
                && validOn(campaign, now)
                && redemptionRepository.existsByCampaign_IdAndUserId(couponCampaignId, clientId)
                && !used(clientId, couponCampaignId);
        if (!valid && campaign.getType() == CampaignType.DISCOUNT && campaign.hasEnded(now)
                && redemptionRepository.existsByCampaign_IdAndUserId(couponCampaignId, clientId) && !used(clientId, couponCampaignId)) {
            log.info("[Coupon] campanha encerrada — campaign={} client={}", couponCampaignId, clientId);
            throw CampaignException.ended(); // ativou e nao usou ate o fim da campanha (DEC-124)
        }
        if (!valid) {
            log.info("[Coupon] cupom fora da lista de ativos — campaign={} client={}", couponCampaignId, clientId);
            throw CampaignException.couponNotActive();
        }
        BigDecimal discount = discountOf(campaign, attendance);
        if (discount.signum() <= 0) {
            throw CampaignException.couponNotActive();
        }
        attendance.setCouponCampaignId(couponCampaignId);
        attendance.setDiscount(discount);
        log.info("[Coupon] aplicado — campaign={} attendance={} desconto R$ {}", couponCampaignId, attendance.getId(), discount);
    }

    /** Ja ha um corte do cliente com este cupom que nao foi desfeito? */
    public boolean used(UUID clientId, UUID campaignId) {
        return attendanceRepository.existsByClient_IdAndCouponCampaignIdAndStatusNotIn(clientId, campaignId, RETURNS_COUPON);
    }

    /** O valor do desconto: o do cupom (fixo ou %) limitado a base da campanha e ao valor do corte. */
    static BigDecimal discountOf(Campaign campaign, Attendance attendance) {
        BigDecimal total = attendance.getTotalValue();
        BigDecimal base = campaign.getBenefitBase() == BenefitBase.WITHOUT_FEE && attendance.getProReceives() != null
                ? attendance.getProReceives() : total;
        BigDecimal value = campaign.getValueAmount() != null
                ? campaign.getValueAmount()
                : base.multiply(campaign.getValuePercent() == null ? BigDecimal.ZERO : campaign.getValuePercent())
                        .divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return value.min(base).min(total).setScale(2, RoundingMode.HALF_UP);
    }

    /** Os dias da campanha contam no fuso da loja. */
    private static boolean validOn(Campaign c, OffsetDateTime when) {
        if (c.getWeekdays() == null) {
            return true;
        }
        String day = when.atZoneSameInstant(BusinessClock.zone()).getDayOfWeek().name();
        return Arrays.asList(c.getWeekdays().split(",")).contains(day);
    }
}
