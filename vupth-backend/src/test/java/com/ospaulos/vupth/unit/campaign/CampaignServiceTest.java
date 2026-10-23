package com.ospaulos.vupth.unit.campaign;

import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.*;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.campaign.service.CampaignService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** O nucleo das campanhas (DEC-64): cada recusa do cupom com o seu codigo, e o aceite. */
class CampaignServiceTest {

    private final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    private final CampaignRedemptionRepository redemptionRepository = mock(CampaignRedemptionRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AttendanceRepository attendanceRepository = mock(AttendanceRepository.class);
    private final CampaignService service =
            new CampaignService(campaignRepository, redemptionRepository, userRepository, attendanceRepository,
                    org.mockito.Mockito.mock(com.ospaulos.vupth.domain.campaign.service.CouponService.class));

    private final UUID userId = UUID.randomUUID();
    private Campaign campaign;
    private BaseUser user;

    @BeforeEach
    void setUp() {
        campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setCode("PARCEIRO10");
        campaign.setName("Parceiros");
        campaign.setType(CampaignType.FEE_WAIVER);
        campaign.setVisibility(CampaignVisibility.PRIVATE);
        campaign.setAudience(CampaignAudience.PROFESSIONAL);
        campaign.setValuePercent(new BigDecimal("100"));
        campaign.setStartsAt(OffsetDateTime.now().minusDays(1));
        campaign.setEndsAt(OffsetDateTime.now().plusDays(27));
        campaign.setMaxRedemptions(2);
        user = new BaseUser();
        user.setId(userId);
        user.addRole(RoleType.CLIENT);
        user.addRole(RoleType.PROFESSIONAL);
        when(campaignRepository.findByCodeIgnoreCase("parceiro10")).thenReturn(Optional.of(campaign));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(redemptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static void assertCode(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CampaignException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("aceita o cupom sem diferenciar maiuscula e devolve a campanha com a hora do aceite")
    void redeems() {
        var r = service.redeem(userId, " parceiro10 ");

        assertThat(r.name()).isEqualTo("Parceiros");
        assertThat(r.benefit()).isEqualTo("DISCOUNT");
        assertThat(r.code()).isEqualTo("PARCEIRO10");
        assertThat(r.redeemedAt()).isNotNull();
        verify(redemptionRepository).save(any());
    }

    @Test
    @DisplayName("cupom que nao existe -> 404 CAMPAIGN_NOT_FOUND")
    void notFound() {
        assertCode(() -> service.redeem(userId, "nada"), HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND");
    }

    @Test
    @DisplayName("desligada, antes do inicio ou depois do fim -> 409 CAMPAIGN_NOT_AVAILABLE")
    void notRunning() {
        campaign.setActive(false);
        assertCode(() -> service.redeem(userId, "parceiro10"), HttpStatus.CONFLICT, "CAMPAIGN_NOT_AVAILABLE");
        campaign.setActive(true);
        campaign.setStartsAt(OffsetDateTime.now().plusDays(1));
        assertCode(() -> service.redeem(userId, "parceiro10"), HttpStatus.CONFLICT, "CAMPAIGN_NOT_AVAILABLE");
        campaign.setStartsAt(OffsetDateTime.now().minusDays(30));
        campaign.setEndsAt(OffsetDateTime.now().minusMinutes(1));
        assertCode(() -> service.redeem(userId, "parceiro10"), HttpStatus.CONFLICT, "CAMPAIGN_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("perfil que nao e o da campanha, ou cliente que ja cortou numa so-para-novos -> 403 CAMPAIGN_NOT_FOR_YOU")
    void notForYou() {
        campaign.setAudience(CampaignAudience.OWNER);
        assertCode(() -> service.redeem(userId, "parceiro10"), HttpStatus.FORBIDDEN, "CAMPAIGN_NOT_FOR_YOU");

        campaign.setAudience(null);
        campaign.setNewClientsOnly(true);
        when(attendanceRepository.existsByClient_IdAndStatusIn(eq(userId), anyList())).thenReturn(true);
        assertCode(() -> service.redeem(userId, "parceiro10"), HttpStatus.FORBIDDEN, "CAMPAIGN_NOT_FOR_YOU");

        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        assertCode(() -> service.redeem(userId, "parceiro10"), HttpStatus.FORBIDDEN, "CAMPAIGN_NOT_FOR_YOU");
    }

    @Test
    @DisplayName("ja aceitou -> 409 CAMPAIGN_ALREADY_REDEEMED; sem vaga -> 409 CAMPAIGN_NOT_AVAILABLE")
    void alreadyAndFull() {
        when(redemptionRepository.existsByCampaign_IdAndUserId(campaign.getId(), userId)).thenReturn(true);
        assertCode(() -> service.redeem(userId, "parceiro10"), HttpStatus.CONFLICT, "CAMPAIGN_ALREADY_REDEEMED");

        when(redemptionRepository.existsByCampaign_IdAndUserId(campaign.getId(), userId)).thenReturn(false);
        when(redemptionRepository.countByCampaign_Id(campaign.getId())).thenReturn(2L);
        assertCode(() -> service.redeem(userId, "parceiro10"), HttpStatus.CONFLICT, "CAMPAIGN_NOT_AVAILABLE");
        verify(redemptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("publicas no ar e os aceitos da pessoa")
    void listings() {
        campaign.setVisibility(CampaignVisibility.PUBLIC);
        when(campaignRepository.findRunning(eq(CampaignVisibility.PUBLIC), any())).thenReturn(List.of(campaign));
        when(redemptionRepository.findByUserIdWithCampaign(userId))
                .thenReturn(List.of(new CampaignRedemption(campaign, userId, OffsetDateTime.now())));

        when(redemptionRepository.countByCampaign_Id(campaign.getId())).thenReturn(1L);
        var active = service.activePublic();
        assertThat(active).extracting("name").containsExactly("Parceiros");
        assertThat(active.get(0).remainingSlots()).isEqualTo(1);
        assertThat(active.get(0).maxRedemptions()).isEqualTo(2);
        assertThat(service.mine(userId)).hasSize(1).allMatch(r -> r.redeemedAt() != null);
    }
}
