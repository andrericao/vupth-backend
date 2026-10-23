package com.ospaulos.vupth.domain.referral.service;

import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.event.AttendanceFinishedEvent;
import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.model.ReferralScope;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.establishment.repository.EstablishmentRepository;
import com.ospaulos.vupth.domain.professional.repository.ProfessionalRepository;
import com.ospaulos.vupth.domain.referral.model.Referral;
import com.ospaulos.vupth.domain.referral.model.ReferralCredit;
import com.ospaulos.vupth.domain.referral.model.ReferralShare;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralRepository;
import com.ospaulos.vupth.domain.shared.BusinessClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/**
 * Bonificacao por link (BAK-9, fatia 2, DEC-52/70/73): o credito do primeiro corte concluido de um cliente
 * indicado. Nenhum valor fixo aqui — o valor, a divisao, quem conta e os dias vem da campanha
 * {@code REFERRAL_BONUS} no ar, que o Andre preenche no painel.
 *
 * <p>Quem recebe: se quem gerou o link atendeu, ele leva tudo ({@code REFERRER}). Se o link e do dono da loja
 * e outro profissional atendeu, dividem pelos percentuais ({@code OWNER} e {@code PROFESSIONAL}). Nos outros
 * casos, quem gerou o link leva tudo.</p>
 *
 * <p>Roda depois do commit do "Finalizar", numa transacao propria: uma falha aqui so e logada.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReferralCreditService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ReferralRepository referralRepository;
    private final ReferralCreditRepository creditRepository;
    private final AttendanceRepository attendanceRepository;
    private final CampaignRepository campaignRepository;
    private final EstablishmentRepository establishmentRepository;
    private final ProfessionalRepository professionalRepository;

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFinished(AttendanceFinishedEvent event) {
        try {
            credit(event.attendanceId());
        } catch (Exception e) {
            log.error("[Referral] credito falhou — attendance={}: {}", event.attendanceId(), e.getMessage(), e);
        }
    }

    /** Cria o credito, se o corte for o primeiro concluido de um cliente indicado e houver campanha que valha. */
    public void credit(UUID attendanceId) {
        Attendance attendance = attendanceRepository.findByIdEager(attendanceId).orElse(null);
        if (attendance == null || attendance.getClient() == null || attendance.getStatus() != AttendanceStatus.FINISHED) {
            return;
        }
        UUID clientId = attendance.getClient().getId();
        Optional<Referral> referral = referralRepository.findById(clientId);
        if (referral.isEmpty() || creditRepository.existsByClientId(clientId)
                || attendanceRepository.countByClient_IdAndStatus(clientId, AttendanceStatus.FINISHED) > 1) {
            return;
        }
        UUID referrerId = referral.get().getReferrerId();
        UUID shopId = attendance.getEstablishment().getId();
        UUID attendedBy = attendance.getProfessional().getId();
        // compartilhe-e-ganhe (DEC-113/115/117): so rende premio quem chegou por uma campanha de adesao (a unica do link) ...
        UUID campaignId = referral.get().getCampaignId();
        if (campaignId == null) {
            log.info("[Referral] indicado sem campanha de adesao — nao paga (attendance={} cliente={})", attendanceId, clientId);
            return;
        }
        // antifraude-emitir-nao-e-usar (DEC-123): quem compartilhou nao usa o proprio link (o claim recusa, REFERRAL_SELF); ja o
        // profissional que trouxe o cliente ao app ganha quando ele corta, com ele ou com outro — por isso nao ha trava por profissional.
        OffsetDateTime when = attendance.getEndTime() != null ? attendance.getEndTime() : OffsetDateTime.now();
        boolean referrerOwnsShop = establishmentRepository.findByIdAndOwnerId(shopId, referrerId).isPresent();
        boolean referrerWorksThere = professionalRepository.findById(referrerId)
                .map(p -> p.getEstablishment() != null && shopId.equals(p.getEstablishment().getId()))
                .orElse(false);

        Optional<Campaign> campaign = campaignRepository.findById(campaignId).stream()
                .filter(c -> c.getType() == CampaignType.REFERRAL_BONUS && c.isRunning(when))
                .filter(c -> c.getValueAmount() != null)
                .filter(c -> validOn(c, when))
                .filter(c -> c.getReferralScope() != ReferralScope.REFERRER_ESTABLISHMENT || referrerOwnsShop || referrerWorksThere)
                .filter(c -> c.getMaxRedemptions() == null
                        || creditRepository.countClientsByCampaign(c.getId()) < c.getMaxRedemptions())
                // teto de gasto da campanha: a soma dos premios (liberados ou nao) nao passa dele
                .filter(c -> c.getMaxTotalCost() == null
                        || creditRepository.sumByCampaign(c.getId()).add(c.getValueAmount()).compareTo(c.getMaxTotalCost()) <= 0)
                .findFirst();
        if (campaign.isEmpty()) {
            log.info("[Referral] sem campanha de indicacao que valha — attendance={} cliente={}", attendanceId, clientId);
            return;
        }
        Campaign c = campaign.get();
        BigDecimal value = c.getValueAmount();
        if (!referrerId.equals(attendedBy) && referrerOwnsShop) {
            BigDecimal professionalPart = value.multiply(c.getReferralProfessionalPercent())
                    .divide(HUNDRED, 2, RoundingMode.HALF_UP);
            save(c, attendance, clientId, attendedBy, ReferralShare.PROFESSIONAL, professionalPart);
            save(c, attendance, clientId, referrerId, ReferralShare.OWNER, value.subtract(professionalPart));
        } else {
            save(c, attendance, clientId, referrerId, ReferralShare.REFERRER, value);
        }
    }

    private void save(Campaign c, Attendance a, UUID clientId, UUID beneficiaryId, ReferralShare share, BigDecimal amount) {
        if (amount.signum() <= 0) {
            return;
        }
        creditRepository.save(new ReferralCredit(c.getId(), a.getId(), clientId, beneficiaryId, share, amount,
                OffsetDateTime.now()));
        log.info("[Referral] credito — campaign={} attendance={} para={} ({}) R$ {}",
                c.getId(), a.getId(), beneficiaryId, share, amount);
    }

    /** Os dias da campanha contam no fuso da loja (um corte de terca 23h em Sao Paulo e terca). */
    private static boolean validOn(Campaign c, OffsetDateTime when) {
        if (c.getWeekdays() == null) {
            return true;
        }
        String day = when.atZoneSameInstant(BusinessClock.zone()).getDayOfWeek().name();
        return Arrays.asList(c.getWeekdays().split(",")).contains(day);
    }
}
