package com.ospaulos.vupth.domain.campaign.service;

import com.ospaulos.vupth.api.campaign.dto.AdminCampaignRequest;
import com.ospaulos.vupth.api.campaign.dto.AdminCampaignResponse;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.model.CampaignVisibility;
import com.ospaulos.vupth.domain.campaign.model.PayoutPeriod;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * O lado do painel das campanhas (campanha-pelo-painel, DEC-69): o Andre cria, edita, pausa, retoma e encerra;
 * nenhuma campanha nasce por migracao. So o papel ADMIN chega aqui ({@code /admin/**} no SecurityConfig).
 *
 * <p>Regras alem dos campos (422 {@code CAMPAIGN_INVALID}): o fim depois do inicio; a privada tem cupom;
 * um valor so (R$ ou %); o desconto e o bonus por indicacao tem valor; o teto de pessoas nao fica abaixo de
 * quem ja aceitou. Cupom repetido (sem diferenciar maiuscula): 409 {@code CAMPAIGN_CODE_TAKEN}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AdminCampaignService {

    private final CampaignRepository campaignRepository;
    private final CampaignRedemptionRepository redemptionRepository;
    private final Clock clock = Clock.systemUTC();

    @Transactional(readOnly = true)
    public List<AdminCampaignResponse> list() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return campaignRepository.findAll(Sort.by(Sort.Direction.DESC, "startsAt")).stream()
                .map(c -> AdminCampaignResponse.of(c, redemptionRepository.countByCampaign_Id(c.getId()), now))
                .toList();
    }

    public AdminCampaignResponse create(UUID adminId, AdminCampaignRequest request) {
        validate(request, null, 0);
        Campaign campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        apply(campaign, request);
        campaignRepository.save(campaign);
        log.info("[Campaign] admin={} criou campaign={} code={}", adminId, campaign.getId(), campaign.getCode());
        return AdminCampaignResponse.of(campaign, 0, OffsetDateTime.now(clock));
    }

    public AdminCampaignResponse update(UUID adminId, UUID id, AdminCampaignRequest request) {
        Campaign campaign = find(id);
        long used = redemptionRepository.countByCampaign_Id(id);
        validate(request, id, used);
        apply(campaign, request);
        log.info("[Campaign] admin={} editou campaign={}", adminId, id);
        return AdminCampaignResponse.of(campaign, used, OffsetDateTime.now(clock));
    }

    public AdminCampaignResponse pause(UUID adminId, UUID id) {
        return setActive(adminId, id, false);
    }

    /** Retomar so dentro da janela: a campanha encerrada nao volta (crie outra). */
    public AdminCampaignResponse resume(UUID adminId, UUID id) {
        Campaign campaign = find(id);
        if (!OffsetDateTime.now(clock).isBefore(campaign.getEndsAt())) {
            throw CampaignException.invalid("A campanha ja terminou; crie outra.");
        }
        return setActive(adminId, id, true);
    }

    /** Encerrar: desliga e o fim passa a ser agora (se ainda nao tinha passado). Quem ja aceitou continua no historico. */
    public AdminCampaignResponse end(UUID adminId, UUID id) {
        Campaign campaign = find(id);
        OffsetDateTime now = OffsetDateTime.now(clock);
        campaign.setActive(false);
        if (now.isBefore(campaign.getEndsAt())) {
            campaign.setEndsAt(now.isBefore(campaign.getStartsAt()) ? campaign.getStartsAt() : now);
        }
        log.info("[Campaign] admin={} encerrou campaign={}", adminId, id);
        return AdminCampaignResponse.of(campaign, redemptionRepository.countByCampaign_Id(id), now);
    }

    private AdminCampaignResponse setActive(UUID adminId, UUID id, boolean active) {
        Campaign campaign = find(id);
        campaign.setActive(active);
        log.info("[Campaign] admin={} {} campaign={}", adminId, active ? "retomou" : "pausou", id);
        return AdminCampaignResponse.of(campaign, redemptionRepository.countByCampaign_Id(id), OffsetDateTime.now(clock));
    }

    private Campaign find(UUID id) {
        return campaignRepository.findById(id).orElseThrow(CampaignException::notFound);
    }

    private void validate(AdminCampaignRequest r, UUID selfId, long used) {
        if (!r.endsAt().isAfter(r.startsAt())) {
            throw CampaignException.invalid("O fim tem de ser depois do inicio.");
        }
        String code = blankToNull(r.code());
        if (r.visibility() == CampaignVisibility.PRIVATE && code == null) {
            throw CampaignException.invalid("A campanha privada precisa de cupom.");
        }
        if (r.valueAmount() != null && r.valuePercent() != null) {
            throw CampaignException.invalid("Um valor so: em reais ou em porcentagem.");
        }
        if (r.type() != CampaignType.FEE_WAIVER && r.valueAmount() == null && r.valuePercent() == null) {
            throw CampaignException.invalid("O desconto e o bonus precisam de valor.");
        }
        validateReferral(r);
        if (r.maxRedemptions() != null && r.maxRedemptions() < used) {
            throw CampaignException.invalid("O limite nao pode ficar abaixo de quem ja aceitou (" + used + ").");
        }
        if (code != null) {
            campaignRepository.findByCodeIgnoreCase(code)
                    .filter(other -> !other.getId().equals(selfId))
                    .ifPresent(other -> { throw CampaignException.codeTaken(); });
        }
    }

    /**
     * Os parametros da bonificacao por link (DEC-73) so existem na campanha de indicacao, e nela todos sao
     * preenchidos pelo Andre: a divisao soma 100, e o dia cabe na periodicidade.
     */
    private static void validateReferral(AdminCampaignRequest r) {
        boolean any = r.referralProfessionalPercent() != null || r.referralOwnerPercent() != null
                || r.referralScope() != null || r.payoutPeriod() != null || r.payoutDay() != null;
        if (r.type() != CampaignType.REFERRAL_BONUS) {
            if (any) {
                throw CampaignException.invalid("Divisao, quem conta e pagamento so valem na campanha de indicacao.");
            }
            return;
        }
        if (r.valueAmount() == null) {
            throw CampaignException.invalid("A indicacao paga em reais (value_amount) por cliente indicado.");
        }
        if (r.referralProfessionalPercent() == null || r.referralOwnerPercent() == null
                || r.referralScope() == null || r.payoutPeriod() == null || r.payoutDay() == null) {
            throw CampaignException.invalid("A indicacao precisa da divisao, de quem conta e do pagamento.");
        }
        if (r.referralProfessionalPercent().add(r.referralOwnerPercent()).compareTo(BigDecimal.valueOf(100)) != 0) {
            throw CampaignException.invalid("A parte do profissional e a do dono somam 100%.");
        }
        if (r.payoutPeriod() == PayoutPeriod.WEEKLY && r.payoutDay() > 7) {
            throw CampaignException.invalid("Na semanal, o dia vai de 1 (segunda) a 7 (domingo).");
        }
    }

    private static void apply(Campaign c, AdminCampaignRequest r) {
        c.setName(r.name().trim());
        c.setDescription(blankToNull(r.description()));
        c.setCode(blankToNull(r.code()));
        c.setType(r.type());
        c.setVisibility(r.visibility());
        c.setAudience(r.audience());
        c.setValueAmount(r.valueAmount());
        c.setValuePercent(r.valuePercent());
        c.setStartsAt(r.startsAt());
        c.setEndsAt(r.endsAt());
        c.setWeekdays(blankToNull(r.weekdays()));
        c.setMaxRedemptions(r.maxRedemptions());
        c.setNewClientsOnly(r.newClientsOnly());
        c.setReferralProfessionalPercent(r.referralProfessionalPercent());
        c.setReferralOwnerPercent(r.referralOwnerPercent());
        c.setReferralScope(r.referralScope());
        c.setPayoutPeriod(r.payoutPeriod());
        c.setPayoutDay(r.payoutDay());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
