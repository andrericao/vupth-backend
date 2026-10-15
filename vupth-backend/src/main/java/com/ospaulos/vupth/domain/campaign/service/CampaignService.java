package com.ospaulos.vupth.domain.campaign.service;

import com.ospaulos.vupth.api.campaign.dto.CampaignResponse;
import com.ospaulos.vupth.domain.attendance.AttendanceStatus;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignAudience;
import com.ospaulos.vupth.domain.campaign.model.CampaignRedemption;
import com.ospaulos.vupth.domain.campaign.model.CampaignVisibility;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * O nucleo das campanhas (promocao-de-lancamento v2, DEC-57/61/64): listar as publicas que estao no ar,
 * aceitar um cupom e ver os aceitos. Criar e medir sao do painel (ADM); ate la, por migracao.
 *
 * <p>Ordem do aceite, cada recusa com o seu codigo: o cupom existe (404) → a campanha esta no ar (409) → o
 * perfil e o da campanha e, se for so para cliente novo, ele nao tem corte concluido (403) → ainda nao
 * aceitou (409) → ainda ha vaga (409).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final CampaignRedemptionRepository redemptionRepository;
    private final UserRepository userRepository;
    private final AttendanceRepository attendanceRepository;
    private final Clock clock = Clock.systemUTC();

    @Transactional(readOnly = true)
    public List<CampaignResponse> activePublic() {
        return campaignRepository.findRunning(CampaignVisibility.PUBLIC, OffsetDateTime.now(clock)).stream()
                .map(c -> CampaignResponse.of(c, true, null))
                .toList();
    }

    public CampaignResponse redeem(UUID userId, String code) {
        Campaign campaign = campaignRepository.findByCodeIgnoreCase(code.trim())
                .orElseThrow(CampaignException::notFound);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!campaign.isRunning(now)) {
            throw CampaignException.notAvailable();
        }
        BaseUser user = userRepository.findById(userId).orElseThrow(CampaignException::notForYou);
        CampaignAudience audience = campaign.getAudience();
        if (audience != null && !user.hasRole(audience.role())) {
            throw CampaignException.notForYou();
        }
        if (campaign.isNewClientsOnly()
                && attendanceRepository.existsByClient_IdAndStatusIn(userId, List.of(AttendanceStatus.FINISHED))) {
            throw CampaignException.notForYou();
        }
        if (redemptionRepository.existsByCampaign_IdAndUserId(campaign.getId(), userId)) {
            throw CampaignException.alreadyRedeemed();
        }
        if (campaign.getMaxRedemptions() != null
                && redemptionRepository.countByCampaign_Id(campaign.getId()) >= campaign.getMaxRedemptions()) {
            throw CampaignException.notAvailable();
        }
        CampaignRedemption redemption = redemptionRepository.save(new CampaignRedemption(campaign, userId, now));
        log.info("[Campaign] cupom aceito — campaign={} user={}", campaign.getId(), userId);
        return CampaignResponse.of(campaign, true, redemption.getRedeemedAt());
    }

    @Transactional(readOnly = true)
    public List<CampaignResponse> mine(UUID userId) {
        return redemptionRepository.findByUserIdWithCampaign(userId).stream()
                .map(r -> CampaignResponse.of(r.getCampaign(), true, r.getRedeemedAt()))
                .toList();
    }
}
