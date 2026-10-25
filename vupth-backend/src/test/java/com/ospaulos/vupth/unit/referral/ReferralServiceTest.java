package com.ospaulos.vupth.unit.referral;

import com.ospaulos.vupth.api.referral.dto.ReferralClaimResponse;
import com.ospaulos.vupth.api.referral.dto.ReferralMeResponse;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignAudience;
import com.ospaulos.vupth.domain.campaign.model.CampaignRedemption;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.model.RedemptionVia;
import com.ospaulos.vupth.domain.referral.exception.ReferralException;
import com.ospaulos.vupth.domain.referral.model.Referral;
import com.ospaulos.vupth.domain.referral.model.ReferralCode;
import com.ospaulos.vupth.domain.referral.repository.ReferralCodeRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralRepository;
import com.ospaulos.vupth.domain.referral.service.ReferralService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.model.valueObject.RoleType;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Indicacao (BAK-9): o codigo fixo de quem indica e a ligacao do cliente, cada recusa com o seu codigo. */
class ReferralServiceTest {

    private final ReferralCodeRepository codeRepository = mock(ReferralCodeRepository.class);
    private final ReferralRepository referralRepository = mock(ReferralRepository.class);
    private final ReferralCreditRepository creditRepository = mock(ReferralCreditRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository redemptionRepository =
            mock(com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository.class);
    private final com.ospaulos.vupth.domain.campaign.repository.CampaignRepository campaignRepository =
            mock(com.ospaulos.vupth.domain.campaign.repository.CampaignRepository.class);
    private final ReferralService service = new ReferralService(codeRepository, referralRepository, creditRepository,
            userRepository, redemptionRepository, campaignRepository, "https://vupth.pages.dev/i/");

    private final UUID referrerId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(referralRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private BaseUser user(UUID id, RoleType role) {
        BaseUser u = new BaseUser();
        u.setId(id);
        u.addRole(role);
        when(userRepository.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    private static void assertCode(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ReferralException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("profissional sem codigo: nasce um de 6 letras legiveis, com o link e quantos chegaram")
    void meCreates() {
        user(referrerId, RoleType.PROFESSIONAL);
        when(codeRepository.findById(referrerId)).thenReturn(Optional.empty());
        when(referralRepository.countByReferrerId(referrerId)).thenReturn(3L);
        when(creditRepository.countClientsByBeneficiaryAndShareIn(eq(referrerId), anyCollection())).thenReturn(2L);
        when(creditRepository.sumUnpaidByBeneficiary(referrerId)).thenReturn(new BigDecimal("10.00"));

        ReferralMeResponse r = service.me(referrerId);

        assertThat(r.code()).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{6}");
        assertThat(r.link()).isEqualTo("https://vupth.pages.dev/i/" + r.code());
        assertThat(r.referredClients()).isEqualTo(3);
        assertThat(r.creditedClients()).isEqualTo(2);
        assertThat(r.toReceive()).isEqualByComparingTo("10.00");
        verify(codeRepository).save(any());
    }

    @Test
    @DisplayName("dono com codigo: devolve o mesmo, sem criar outro")
    void meKeeps() {
        user(referrerId, RoleType.OWNER);
        when(codeRepository.findById(referrerId))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", OffsetDateTime.now())));

        assertThat(service.me(referrerId).code()).isEqualTo("K7M2QX");
        verify(codeRepository, never()).save(any());
    }

    @Test
    @DisplayName("codigo repetido no sorteio: tenta de novo; cinco repetidos seguidos, erro")
    void meRetriesCollision() {
        user(referrerId, RoleType.PROFESSIONAL);
        when(codeRepository.findById(referrerId)).thenReturn(Optional.empty());
        when(codeRepository.existsByCode(anyString())).thenReturn(true, false);

        assertThat(service.me(referrerId).code()).hasSize(6);
        verify(codeRepository, times(2)).existsByCode(anyString());

        when(codeRepository.existsByCode(anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.me(referrerId)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("o codigo vale para todo papel (cliente tambem); usuario que nao existe pedindo link: 403")
    void meForEveryRole() {
        user(clientId, RoleType.CLIENT);
        when(codeRepository.findById(clientId)).thenReturn(Optional.empty());
        assertThat(service.me(clientId).code()).hasSize(6);
        UUID ghost = UUID.randomUUID();
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());
        assertCode(() -> service.me(ghost), HttpStatus.FORBIDDEN, "REFERRAL_NOT_FOR_YOU");
    }

    private Campaign campaign(CampaignAudience audience, boolean running) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setName("Indique " + c.getId());
        c.setType(CampaignType.REFERRAL_BONUS);
        c.setAudience(audience);
        c.setStartsAt(OffsetDateTime.now().minusDays(1));
        c.setEndsAt(running ? OffsetDateTime.now().plusDays(5) : OffsetDateTime.now().minusHours(1));
        return c;
    }

    private CampaignRedemption joined(Campaign c, UUID userId) {
        return new CampaignRedemption(c, userId, OffsetDateTime.now(), RedemptionVia.PARTICIPAR);
    }

    @Test
    @DisplayName("me traz so as campanhas de link em que entrou, no ar e do seu papel, cada uma com o seu link e os seus numeros")
    void meListsCampaigns() {
        user(referrerId, RoleType.PROFESSIONAL);
        when(codeRepository.findById(referrerId))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", OffsetDateTime.now())));
        Campaign open = campaign(null, true);
        Campaign ended = campaign(null, false);
        Campaign forClients = campaign(CampaignAudience.CLIENT, true);
        when(redemptionRepository.findReferralParticipations(referrerId))
                .thenReturn(List.of(joined(ended, referrerId), joined(forClients, referrerId), joined(open, referrerId)));
        when(referralRepository.countByReferrerIdAndCampaignId(referrerId, open.getId())).thenReturn(4L);
        when(creditRepository.countClientsByBeneficiaryAndCampaign(eq(referrerId), eq(open.getId()), anyCollection())).thenReturn(1L);
        when(creditRepository.sumPendingReleaseByBeneficiary(referrerId)).thenReturn(new BigDecimal("7.50"));

        ReferralMeResponse r = service.me(referrerId);

        assertThat(r.campaigns()).hasSize(1);
        assertThat(r.campaigns().get(0).campaignId()).isEqualTo(open.getId());
        assertThat(r.campaigns().get(0).link()).isEqualTo("https://vupth.pages.dev/i/K7M2QX?c=" + open.getId());
        assertThat(r.campaigns().get(0).referredClients()).isEqualTo(4);
        assertThat(r.campaigns().get(0).creditedClients()).isEqualTo(1);
        assertThat(r.pendingRelease()).isEqualByComparingTo("7.50");
    }

    @Test
    @DisplayName("claim escolhe UMA campanha: a do link se quem indicou entrou nela, senao a adesao mais antiga no ar; sem campanha, registra sem premio")
    void claimChoosesOneCampaign() {
        user(referrerId, RoleType.PROFESSIONAL);
        when(codeRepository.findByCode("K7M2QX"))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", OffsetDateTime.now())));
        when(referralRepository.findById(clientId)).thenReturn(Optional.empty());
        Campaign older = campaign(null, true);
        Campaign newer = campaign(null, true);
        when(redemptionRepository.findReferralParticipations(referrerId))
                .thenReturn(List.of(joined(older, referrerId), joined(newer, referrerId)));

        assertThat(service.claim(clientId, "K7M2QX", newer.getId()).campaignId()).as("a do link").isEqualTo(newer.getId());
        assertThat(service.claim(clientId, "K7M2QX", UUID.randomUUID()).campaignId()).as("link velho: a mais antiga")
                .isEqualTo(older.getId());
        assertThat(service.claim(clientId, "K7M2QX").campaignId()).isEqualTo(older.getId());

        when(redemptionRepository.findReferralParticipations(referrerId)).thenReturn(List.of());
        assertThat(service.claim(clientId, "K7M2QX", null).campaignId()).as("antes da adesao: sem premio").isNull();
        verify(referralRepository, times(4)).save(any());
    }

    @Test
    @DisplayName("DEC-124: o link de campanha encerrada ou esgotada abre, mas o claim devolve CAMPAIGN_ENDED / CAMPAIGN_SOLD_OUT (prazo primeiro)")
    void claimEndedOrSoldOut() {
        user(referrerId, RoleType.PROFESSIONAL);
        when(codeRepository.findByCode("K7M2QX"))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", OffsetDateTime.now())));
        when(referralRepository.findById(clientId)).thenReturn(Optional.empty());
        Campaign link = campaign(null, false);
        link.setMaxRedemptions(1);
        when(campaignRepository.findById(link.getId())).thenReturn(Optional.of(link));
        when(referralRepository.countByCampaignIdAndSlotTakenAtIsNotNull(link.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.claim(clientId, "K7M2QX", link.getId()))
                .isInstanceOfSatisfying(com.ospaulos.vupth.domain.campaign.exception.CampaignException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CAMPAIGN_ENDED"));

        link.setEndsAt(OffsetDateTime.now().plusDays(5));
        assertThatThrownBy(() -> service.claim(clientId, "K7M2QX", link.getId()))
                .isInstanceOfSatisfying(com.ospaulos.vupth.domain.campaign.exception.CampaignException.class,
                        e -> assertThat(e.getCode()).isEqualTo("CAMPAIGN_SOLD_OUT"));
        verify(referralRepository, never()).save(any());
    }

    @Test
    @DisplayName("DEC-124: uso unico do link por cliente — outra campanha do mesmo indicador e 409; a corrida de dois claims cai no mesmo resultado")
    void claimOncePerClient() {
        user(referrerId, RoleType.PROFESSIONAL);
        when(codeRepository.findByCode("K7M2QX"))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", OffsetDateTime.now())));
        UUID first = UUID.randomUUID();
        Referral linked = new Referral(clientId, referrerId, OffsetDateTime.now(), first);
        when(referralRepository.findById(clientId)).thenReturn(Optional.of(linked));

        assertThat(service.claim(clientId, "K7M2QX", first).campaignId()).isEqualTo(first);
        assertThat(service.claim(clientId, "K7M2QX", null).campaignId()).isEqualTo(first);
        assertCode(() -> service.claim(clientId, "K7M2QX", UUID.randomUUID()), HttpStatus.CONFLICT, "REFERRAL_ALREADY_SET");

        // corrida: nao havia ligacao ao conferir, mas a outra tentativa gravou primeiro (chave do cliente)
        when(referralRepository.findById(clientId)).thenReturn(Optional.empty(), Optional.of(linked));
        when(referralRepository.save(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));
        assertThat(service.claim(clientId, "K7M2QX", first).campaignId()).isEqualTo(first);
    }

    @Test
    @DisplayName("claim: liga o cliente (codigo sem diferenciar maiuscula e sem espaco)")
    void claimLinks() {
        when(codeRepository.findByCode("K7M2QX"))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", OffsetDateTime.now())));
        when(referralRepository.findById(clientId)).thenReturn(Optional.empty());

        ReferralClaimResponse r = service.claim(clientId, " k7m2qx ");

        assertThat(r.code()).isEqualTo("K7M2QX");
        assertThat(r.referredAt()).isNotNull();
        verify(referralRepository).save(argThat(ref -> ref.getClientId().equals(clientId)
                && ref.getReferrerId().equals(referrerId)));
    }

    @Test
    @DisplayName("claim de novo com o mesmo codigo: inofensivo; com outro: 409; o proprio: 422; nao existe: 404")
    void claimRules() {
        OffsetDateTime first = OffsetDateTime.now().minusDays(2);
        when(codeRepository.findByCode("K7M2QX"))
                .thenReturn(Optional.of(new ReferralCode(referrerId, "K7M2QX", first)));
        when(referralRepository.findById(clientId)).thenReturn(Optional.of(new Referral(clientId, referrerId, first)));

        assertThat(service.claim(clientId, "K7M2QX").referredAt()).isEqualTo(first);

        UUID other = UUID.randomUUID();
        when(codeRepository.findByCode("OTHER2"))
                .thenReturn(Optional.of(new ReferralCode(other, "OTHER2", first)));
        assertCode(() -> service.claim(clientId, "OTHER2"), HttpStatus.CONFLICT, "REFERRAL_ALREADY_SET");

        assertCode(() -> service.claim(referrerId, "K7M2QX"), HttpStatus.UNPROCESSABLE_ENTITY, "REFERRAL_SELF");

        when(codeRepository.findByCode("NOPE22")).thenReturn(Optional.empty());
        assertCode(() -> service.claim(clientId, "NOPE22"), HttpStatus.NOT_FOUND, "REFERRAL_NOT_FOUND");
        verify(referralRepository, never()).save(any());
    }
}
