package com.ospaulos.vupth.unit.campaign;

import com.ospaulos.vupth.api.campaign.dto.AdminCampaignRequest;
import com.ospaulos.vupth.api.campaign.dto.AdminCampaignResponse;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.*;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.campaign.service.AdminCampaignService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
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

/** campanha-pelo-painel (DEC-69): criar, editar, pausar, retomar e encerrar, cada recusa com o seu codigo. */
class AdminCampaignServiceTest {

    private final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    private final CampaignRedemptionRepository redemptionRepository = mock(CampaignRedemptionRepository.class);
    private final AdminCampaignService service = new AdminCampaignService(campaignRepository, redemptionRepository);

    private final UUID adminId = UUID.randomUUID();
    private final OffsetDateTime now = OffsetDateTime.now();
    private Campaign stored;

    @BeforeEach
    void setUp() {
        stored = new Campaign();
        stored.setId(UUID.randomUUID());
        stored.setCode("PARCEIRO10");
        stored.setName("Parceiros");
        stored.setType(CampaignType.DISCOUNT);
        stored.setVisibility(CampaignVisibility.PRIVATE);
        stored.setValueAmount(new BigDecimal("10.00"));
        stored.setStartsAt(now.minusDays(1));
        stored.setEndsAt(now.plusDays(10));
        when(campaignRepository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(campaignRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static AdminCampaignRequest request(String code, CampaignType type, CampaignVisibility visibility,
                                                BigDecimal amount, BigDecimal percent,
                                                OffsetDateTime starts, OffsetDateTime ends, Integer max) {
        return new AdminCampaignRequest(" Lancamento ", " ", code, type, visibility, CampaignAudience.CLIENT, amount,
                percent, starts, ends, "TUESDAY,WEDNESDAY", max, true);
    }

    private AdminCampaignRequest valid(String code) {
        return request(code, CampaignType.DISCOUNT, CampaignVisibility.PRIVATE, new BigDecimal("5.00"), null,
                now.minusHours(1), now.plusDays(30), 50);
    }

    private static void assertCode(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(CampaignException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("cria com os campos do painel: nome sem espaco, descricao vazia vira nula, no ar e com 0 aceites")
    void creates() {
        AdminCampaignResponse r = service.create(adminId, valid("VUPTH5"));

        assertThat(r.name()).isEqualTo("Lancamento");
        assertThat(r.description()).isNull();
        assertThat(r.code()).isEqualTo("VUPTH5");
        assertThat(r.audience()).isEqualTo("CLIENT");
        assertThat(r.weekdays()).isEqualTo("TUESDAY,WEDNESDAY");
        assertThat(r.maxRedemptions()).isEqualTo(50);
        assertThat(r.newClientsOnly()).isTrue();
        assertThat(r.redemptions()).isZero();
        assertThat(r.status()).isEqualTo("RUNNING");
        verify(campaignRepository).save(any());
    }

    @Test
    @DisplayName("publica sem cupom e isencao sem valor: aceitas; a que comeca amanha fica SCHEDULED")
    void publicFeeWaiverScheduled() {
        AdminCampaignResponse r = service.create(adminId, request(null, CampaignType.FEE_WAIVER, CampaignVisibility.PUBLIC,
                null, null, now.plusDays(1), now.plusDays(5), null));

        assertThat(r.code()).isNull();
        assertThat(r.status()).isEqualTo("SCHEDULED");
        verify(campaignRepository, never()).findByCodeIgnoreCase(anyString());
    }

    @Test
    @DisplayName("422 CAMPAIGN_INVALID: fim antes do inicio, privada sem cupom, dois valores, desconto sem valor")
    void invalid() {
        assertCode(() -> service.create(adminId, request("A", CampaignType.DISCOUNT, CampaignVisibility.PRIVATE,
                BigDecimal.ONE, null, now, now, null)), HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN_INVALID");
        assertCode(() -> service.create(adminId, request(" ", CampaignType.DISCOUNT, CampaignVisibility.PRIVATE,
                BigDecimal.ONE, null, now, now.plusDays(1), null)), HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN_INVALID");
        assertCode(() -> service.create(adminId, request("A", CampaignType.DISCOUNT, CampaignVisibility.PRIVATE,
                BigDecimal.ONE, BigDecimal.TEN, now, now.plusDays(1), null)), HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN_INVALID");
        assertCode(() -> service.create(adminId, request("A", CampaignType.REFERRAL_BONUS, CampaignVisibility.PRIVATE,
                null, null, now, now.plusDays(1), null)), HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN_INVALID");
        verify(campaignRepository, never()).save(any());
    }

    @Test
    @DisplayName("cupom de outra campanha: 409 CAMPAIGN_CODE_TAKEN; o da propria campanha pode ficar na edicao")
    void codeTaken() {
        when(campaignRepository.findByCodeIgnoreCase("PARCEIRO10")).thenReturn(Optional.of(stored));

        assertCode(() -> service.create(adminId, valid("PARCEIRO10")), HttpStatus.CONFLICT, "CAMPAIGN_CODE_TAKEN");

        AdminCampaignResponse r = service.update(adminId, stored.getId(), valid("PARCEIRO10"));
        assertThat(r.name()).isEqualTo("Lancamento");
        assertThat(stored.getValueAmount()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("editar: o limite nao fica abaixo de quem ja aceitou; campanha que nao existe: 404")
    void updateRules() {
        when(redemptionRepository.countByCampaign_Id(stored.getId())).thenReturn(60L);

        assertCode(() -> service.update(adminId, stored.getId(), valid("NOVO")), HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN_INVALID");
        assertCode(() -> service.update(adminId, UUID.randomUUID(), valid("NOVO")), HttpStatus.NOT_FOUND, "CAMPAIGN_NOT_FOUND");
        assertThat(stored.getCode()).isEqualTo("PARCEIRO10");
    }

    @Test
    @DisplayName("pausar e retomar: PAUSED e de volta RUNNING, com os aceites contados")
    void pauseAndResume() {
        when(redemptionRepository.countByCampaign_Id(stored.getId())).thenReturn(3L);

        AdminCampaignResponse paused = service.pause(adminId, stored.getId());
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.active()).isFalse();
        assertThat(paused.redemptions()).isEqualTo(3);

        assertThat(service.resume(adminId, stored.getId()).status()).isEqualTo("RUNNING");
        assertThat(stored.isActive()).isTrue();
    }

    @Test
    @DisplayName("encerrar: desliga e o fim vira agora; depois nao retoma (422)")
    void endThenNoResume() {
        AdminCampaignResponse r = service.end(adminId, stored.getId());

        assertThat(r.status()).isEqualTo("ENDED");
        assertThat(stored.isActive()).isFalse();
        assertThat(stored.getEndsAt()).isBefore(now.plusMinutes(1));
        assertCode(() -> service.resume(adminId, stored.getId()), HttpStatus.UNPROCESSABLE_ENTITY, "CAMPAIGN_INVALID");
    }

    @Test
    @DisplayName("encerrar a que ja acabou nao mexe no fim; a que nem comecou termina no proprio inicio")
    void endKeepsWindowCoherent() {
        OffsetDateTime past = now.minusDays(2);
        stored.setEndsAt(past);
        service.end(adminId, stored.getId());
        assertThat(stored.getEndsAt()).isEqualTo(past);

        OffsetDateTime future = now.plusDays(3);
        stored.setStartsAt(future);
        stored.setEndsAt(now.plusDays(9));
        service.end(adminId, stored.getId());
        assertThat(stored.getEndsAt()).isEqualTo(future);
    }

    @Test
    @DisplayName("lista todas, da mais nova para a mais velha, com os aceites de cada uma")
    void lists() {
        when(campaignRepository.findAll(any(Sort.class))).thenReturn(List.of(stored));
        when(redemptionRepository.countByCampaign_Id(stored.getId())).thenReturn(7L);

        List<AdminCampaignResponse> all = service.list();

        assertThat(all).singleElement().satisfies(c -> {
            assertThat(c.redemptions()).isEqualTo(7);
            assertThat(c.visibility()).isEqualTo("PRIVATE");
            assertThat(c.audience()).isNull();
        });
        verify(campaignRepository).findAll(Sort.by(Sort.Direction.DESC, "startsAt"));
    }
}
