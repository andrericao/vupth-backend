package com.ospaulos.vupth.unit.campaign;

import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.*;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.campaign.service.CampaignService;
import com.ospaulos.vupth.domain.campaign.service.CouponService;
import com.ospaulos.vupth.domain.referral.repository.ReferralRepository;
import com.ospaulos.vupth.domain.serviceItem.model.ServiceItem;
import com.ospaulos.vupth.domain.serviceItem.repository.ServiceItemRepository;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** previa-publica-do-desconto (BAK-1): o preco com cupom sem login, e as travas que fecham o resto no mesmo 404. */
class CampaignPreviewTest {

    private final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    private final CampaignRedemptionRepository redemptionRepository = mock(CampaignRedemptionRepository.class);
    private final ServiceItemRepository serviceItemRepository = mock(ServiceItemRepository.class);
    private final CampaignService service = new CampaignService(campaignRepository, redemptionRepository,
            mock(UserRepository.class), mock(AttendanceRepository.class), mock(CouponService.class),
            serviceItemRepository, mock(ReferralRepository.class));

    private Campaign campaign;
    private final UUID itemId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        campaign = new Campaign();
        campaign.setId(UUID.randomUUID());
        campaign.setCode("LINK10");
        campaign.setType(CampaignType.DISCOUNT);
        campaign.setVisibility(CampaignVisibility.PUBLIC);
        campaign.setValueAmount(new BigDecimal("10.00"));
        campaign.setStartsAt(OffsetDateTime.now().minusDays(1));
        campaign.setEndsAt(OffsetDateTime.now().plusDays(5));
        when(campaignRepository.findById(campaign.getId())).thenAnswer(inv -> Optional.of(campaign));
        ServiceItem item = new ServiceItem();
        item.setPrice(new BigDecimal("30.00"));
        item.setClientPrice(new BigDecimal("32.90"));
        ReflectionTestUtils.setField(item, "proReceives", new BigDecimal("30.00"));
        when(serviceItemRepository.findById(itemId)).thenReturn(Optional.of(item));
    }

    private void assertNotFound(String ref) {
        assertThatThrownBy(() -> service.preview(campaign.getId(), itemId, ref)).isInstanceOfSatisfying(CampaignException.class,
                e -> assertThat(e.getCode()).isEqualTo("CAMPAIGN_NOT_FOUND"));
    }

    @Test
    @DisplayName("campanha publica no ar: valor cheio, desconto e valor com cupom (R$ 32,90 - R$ 10,00 = R$ 22,90)")
    void publicPreview() {
        var r = service.preview(campaign.getId(), itemId, null);
        assertThat(r.fullPrice()).isEqualByComparingTo("32.90");
        assertThat(r.discount()).isEqualByComparingTo("10.00");
        assertThat(r.priceWithCoupon()).isEqualByComparingTo("22.90");
    }

    @Test
    @DisplayName("percentual sobre o preco sem a taxa (WITHOUT_FEE) e limitado a essa base")
    void percentAndBase() {
        campaign.setValueAmount(null);
        campaign.setValuePercent(new BigDecimal("10"));
        campaign.setBenefitBase(BenefitBase.WITHOUT_FEE);
        var r = service.preview(campaign.getId(), itemId, null);
        assertThat(r.discount()).isEqualByComparingTo("3.00");
        assertThat(r.benefitBase()).isEqualTo("WITHOUT_FEE");

        campaign.setValuePercent(null);
        campaign.setValueAmount(new BigDecimal("99.00"));
        assertThat(service.preview(campaign.getId(), itemId, null).priceWithCoupon()).isEqualByComparingTo("2.90"); // limitado a base sem a taxa (R$ 30,00)
    }

    @Test
    @DisplayName("privada so com o codigo do link (sem diferenciar maiuscula); sem ele, ou com outro, e 404")
    void privateNeedsRef() {
        campaign.setVisibility(CampaignVisibility.PRIVATE);
        assertNotFound(null);
        assertNotFound("outro");
        assertThat(service.preview(campaign.getId(), itemId, " link10 ").priceWithCoupon()).isEqualByComparingTo("22.90");
    }

    @Test
    @DisplayName("o codigo da campanha so sai quando o ref do link e valido: o convidado resgata; sem ref (publica) ou ref errado, nada")
    void codeOnlyWithValidRef() {
        campaign.setVisibility(CampaignVisibility.PRIVATE);
        assertThat(service.preview(campaign.getId(), itemId, "LINK10").code()).isEqualTo("LINK10");
        assertThat(service.preview(campaign.getId(), itemId, " link10 ").code()).as("devolve o codigo da campanha, nao o digitado").isEqualTo("LINK10");
        assertNotFound("outro");
        campaign.setVisibility(CampaignVisibility.PUBLIC);
        assertThat(service.preview(campaign.getId(), itemId, null).code()).isNull();
        assertThat(service.preview(campaign.getId(), itemId, "outro").code()).isNull();
    }

    @Test
    @DisplayName("o mesmo 404 para campanha que nao existe, de outro tipo, desligada, encerrada, esgotada ou so para profissional")
    void everythingElseIsTheSame404() {
        campaign.setType(CampaignType.REFERRAL_BONUS);
        assertNotFound(null);
        campaign.setType(CampaignType.DISCOUNT);
        campaign.setActive(false);
        assertNotFound(null);
        campaign.setActive(true);
        campaign.setEndsAt(OffsetDateTime.now().minusMinutes(1));
        assertNotFound(null);
        campaign.setEndsAt(OffsetDateTime.now().plusDays(5));
        campaign.setAudience(CampaignAudience.PROFESSIONAL);
        assertNotFound(null);
        campaign.setAudience(CampaignAudience.CLIENT);
        campaign.setMaxRedemptions(1);
        when(redemptionRepository.countByCampaign_Id(campaign.getId())).thenReturn(1L);
        assertNotFound(null);
        assertThatThrownBy(() -> service.preview(UUID.randomUUID(), itemId, null)).isInstanceOf(CampaignException.class);
    }

    @Test
    @DisplayName("servico que nao existe: 404; sem preco do cliente (legado), vale o preco")
    void serviceItem() {
        assertThatThrownBy(() -> service.preview(campaign.getId(), UUID.randomUUID(), null)).isInstanceOf(CampaignException.class);
        ServiceItem legacy = new ServiceItem();
        legacy.setPrice(new BigDecimal("30.00"));
        UUID legacyId = UUID.randomUUID();
        when(serviceItemRepository.findById(legacyId)).thenReturn(Optional.of(legacy));
        assertThat(service.preview(campaign.getId(), legacyId, null).priceWithCoupon()).isEqualByComparingTo("20.00");
    }
}
