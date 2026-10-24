package com.ospaulos.vupth.domain.referral.service;

import com.ospaulos.vupth.api.referral.dto.ReferralClaimResponse;
import com.ospaulos.vupth.api.referral.dto.ReferralMeResponse;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignRedemption;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.referral.exception.ReferralException;
import com.ospaulos.vupth.domain.referral.model.Referral;
import com.ospaulos.vupth.domain.referral.model.ReferralCode;
import com.ospaulos.vupth.domain.referral.model.ReferralShare;
import com.ospaulos.vupth.domain.referral.repository.ReferralCodeRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralRepository;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Bonificacao por link (BAK-9, DEC-52/70/73), a ligacao: cada profissional e cada dono tem um codigo fixo; o
 * cliente que chega pelo link fica ligado a quem o indicou. Vale o primeiro codigo, que nao muda depois. O
 * credito por corte concluido usa esta ligacao e os parametros da campanha de indicacao.
 */
@Slf4j
@Service
@Transactional
public class ReferralService {

    /** Sem 0/O, 1/I/L: o codigo e lido e digitado por gente. */
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final int CODE_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 5;

    private final ReferralCodeRepository codeRepository;
    private final ReferralRepository referralRepository;
    private final ReferralCreditRepository creditRepository;
    private final UserRepository userRepository;
    private final CampaignRedemptionRepository redemptionRepository;
    private final CampaignRepository campaignRepository;
    private final String linkBase;
    private final SecureRandom random = new SecureRandom();
    private final Clock clock = Clock.systemUTC();

    public ReferralService(ReferralCodeRepository codeRepository, ReferralRepository referralRepository,
                           ReferralCreditRepository creditRepository, UserRepository userRepository,
                           CampaignRedemptionRepository redemptionRepository, CampaignRepository campaignRepository,
                           @Value("${vupth.referral.link-base:https://vupth.pages.dev/i/}") String linkBase) {
        this.codeRepository = codeRepository;
        this.referralRepository = referralRepository;
        this.creditRepository = creditRepository;
        this.userRepository = userRepository;
        this.redemptionRepository = redemptionRepository;
        this.campaignRepository = campaignRepository;
        this.linkBase = linkBase;
    }

    /**
     * O link de quem indica; o codigo nasce no primeiro pedido e vale para todo papel (cliente, profissional e dono, DEC-113). Traz
     * tambem as campanhas de link em que a pessoa entrou, cada uma com o seu link ({@code ?c=}), para escolher qual compartilhar.
     */
    public ReferralMeResponse me(UUID userId) {
        BaseUser user = userRepository.findById(userId).orElseThrow(ReferralException::notForYou);
        ReferralCode code = codeRepository.findById(userId).orElseGet(() -> create(userId));
        String link = linkBase + code.getCode();
        List<ReferralMeResponse.CampaignLink> campaigns = participations(user).stream()
                .map(c -> new ReferralMeResponse.CampaignLink(c.getId(), c.getName(), link + "?c=" + c.getId(), c.getEndsAt(),
                        referralRepository.countByReferrerIdAndCampaignId(userId, c.getId()),
                        creditRepository.countClientsByBeneficiaryAndCampaign(userId, c.getId(),
                                List.of(ReferralShare.REFERRER, ReferralShare.OWNER))))
                .toList();
        return new ReferralMeResponse(code.getCode(), link, referralRepository.countByReferrerId(userId),
                creditRepository.countClientsByBeneficiaryAndShareIn(userId, List.of(ReferralShare.REFERRER, ReferralShare.OWNER)),
                creditRepository.sumUnpaidByBeneficiary(userId), creditRepository.sumPendingReleaseByBeneficiary(userId), campaigns);
    }

    public ReferralClaimResponse claim(UUID clientId, String rawCode) {
        return claim(clientId, rawCode, null);
    }

    /**
     * Liga o cliente a quem gerou o codigo. Repetir o mesmo codigo e inofensivo; outro codigo, 409. A campanha que rende o premio e
     * UMA so (nao acumulam): a do link ({@code campaignId}), se quem indicou entrou nela e ela esta no ar; senao, a adesao mais antiga
     * ainda aberta. Quem indicou sem estar em nenhuma campanha: o registro existe (para medir), mas nao paga.
     */
    public ReferralClaimResponse claim(UUID clientId, String rawCode, UUID campaignId) {
        String normalized = rawCode.trim().toUpperCase(Locale.ROOT);
        ReferralCode code = codeRepository.findByCode(normalized).orElseThrow(ReferralException::notFound);
        if (code.getUserId().equals(clientId)) {
            throw ReferralException.self();
        }
        Optional<Referral> existing = referralRepository.findById(clientId);
        if (existing.isPresent()) {
            return sameClaim(existing.get(), code, campaignId);
        }
        requireOpen(campaignId);
        UUID chosen = userRepository.findById(code.getUserId())
                .map(referrer -> chooseCampaign(referrer, campaignId)).orElse(null);
        Referral referral;
        try {
            referral = referralRepository.save(new Referral(clientId, code.getUserId(), OffsetDateTime.now(clock), chosen));
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            // uso unico por cliente: duas tentativas ao mesmo tempo, a chave do cliente (referral.client_id) deixa passar so uma
            return sameClaim(referralRepository.findById(clientId).orElseThrow(ReferralException::alreadySet), code, campaignId);
        }
        log.info("[Referral] cliente={} indicado por={} campanha={}", clientId, code.getUserId(), chosen);
        return new ReferralClaimResponse(code.getCode(), referral.getCreatedAt(), chosen);
    }

    /**
     * O cliente ja esta ligado a alguem (uso unico por cliente, DEC-124): repetir o mesmo codigo e a mesma campanha do link e
     * inofensivo; outro codigo, ou outra campanha da qual ele ja saiu escolhida, e 409 REFERRAL_ALREADY_SET.
     */
    private ReferralClaimResponse sameClaim(Referral existing, ReferralCode code, UUID campaignId) {
        boolean otherCampaign = campaignId != null && existing.getCampaignId() != null && !campaignId.equals(existing.getCampaignId());
        if (!existing.getReferrerId().equals(code.getUserId()) || otherCampaign) {
            throw ReferralException.alreadySet();
        }
        return new ReferralClaimResponse(code.getCode(), existing.getCreatedAt(), existing.getCampaignId());
    }

    /**
     * campanha-vaga-e-prazo (DEC-124): o link de uma campanha encerrada ou esgotada continua abrindo, mas o claim devolve o erro padrao
     * (CAMPAIGN_ENDED ou CAMPAIGN_SOLD_OUT; primeiro o prazo, depois a vaga). Sem campanha no link, nada a conferir.
     */
    private void requireOpen(UUID campaignId) {
        if (campaignId == null) {
            return;
        }
        campaignRepository.findById(campaignId).filter(c -> c.getType() == CampaignType.REFERRAL_BONUS).ifPresent(c -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (c.hasEnded(now)) {
                throw CampaignException.ended();
            }
            if (!c.isRunning(now)) {
                throw CampaignException.notAvailable();
            }
            if (c.getMaxRedemptions() != null
                    && referralRepository.countByCampaignIdAndSlotTakenAtIsNotNull(c.getId()) >= c.getMaxRedemptions()) {
                throw CampaignException.soldOut();
            }
        });
    }

    private UUID chooseCampaign(BaseUser referrer, UUID wanted) {
        List<Campaign> open = participations(referrer);
        if (wanted != null && open.stream().anyMatch(c -> c.getId().equals(wanted))) {
            return wanted;
        }
        return open.isEmpty() ? null : open.get(0).getId();
    }

    /** As campanhas de link no ar em que a pessoa entrou e que cobrem o papel dela; a adesao mais antiga primeiro. */
    private List<Campaign> participations(BaseUser user) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return redemptionRepository.findReferralParticipations(user.getId()).stream()
                .map(CampaignRedemption::getCampaign)
                .filter(c -> c.isRunning(now))
                .filter(c -> c.getAudience() == null || user.hasRole(c.getAudience().role()))
                .toList();
    }

    private ReferralCode create(UUID userId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!codeRepository.existsByCode(candidate)) {
                log.info("[Referral] codigo criado para user={}", userId);
                return codeRepository.save(new ReferralCode(userId, candidate, OffsetDateTime.now(clock)));
            }
        }
        throw new IllegalStateException("Nao foi possivel gerar um codigo de indicacao unico");
    }

    String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
