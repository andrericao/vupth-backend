package com.ospaulos.vupth.unit.campaign;

import com.ospaulos.vupth.domain.attendance.model.Attendance;
import com.ospaulos.vupth.domain.attendance.repository.AttendanceRepository;
import com.ospaulos.vupth.domain.campaign.exception.CampaignException;
import com.ospaulos.vupth.domain.campaign.model.BenefitBase;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.CampaignAudience;
import com.ospaulos.vupth.domain.campaign.model.CampaignType;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRedemptionRepository;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.campaign.service.CouponService;
import com.ospaulos.vupth.domain.client.model.Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** O desconto do cupom ativado (cupom-ativado-em-configuracoes): a base, o limite e cada motivo de recusa. */
class CouponServiceTest {

    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final CampaignRedemptionRepository redemptions = mock(CampaignRedemptionRepository.class);
    private final AttendanceRepository attendances = mock(AttendanceRepository.class);
    private final CouponService service = new CouponService(campaigns, redemptions, attendances);

    private final UUID clientId = UUID.randomUUID();

    private Campaign campaign(BigDecimal amount, BigDecimal percent, BenefitBase base) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setType(CampaignType.DISCOUNT);
        c.setValueAmount(amount);
        c.setValuePercent(percent);
        c.setBenefitBase(base);
        c.setStartsAt(OffsetDateTime.now().minusDays(1));
        c.setEndsAt(OffsetDateTime.now().plusDays(1));
        when(campaigns.findById(c.getId())).thenReturn(Optional.of(c));
        when(redemptions.existsByCampaign_IdAndUserId(c.getId(), clientId)).thenReturn(true);
        return c;
    }

    private Attendance attendance(String total, String proReceives) {
        Attendance a = new Attendance();
        a.setId(UUID.randomUUID());
        Client client = new Client();
        client.setId(clientId);
        a.setClient(client);
        a.setTotalValue(new BigDecimal(total));
        ReflectionTestUtils.setField(a, "proReceives", proReceives == null ? null : new BigDecimal(proReceives));
        return a;
    }

    @Test
    @DisplayName("valor fixo: abate o valor; a base sem taxa usa o que o profissional recebe; nunca passa do corte")
    void fixedValue() {
        Campaign c = campaign(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        Attendance a = attendance("30.90", "28.00");
        service.apply(a, c.getId());
        assertThat(a.getDiscount()).isEqualByComparingTo("5.00");
        assertThat(a.getCouponCampaignId()).isEqualTo(c.getId());
        assertThat(a.getAmountDue()).isEqualByComparingTo("25.90");

        Campaign big = campaign(new BigDecimal("50.00"), null, BenefitBase.WITHOUT_FEE);
        Attendance b = attendance("30.90", "28.00");
        service.apply(b, big.getId());
        assertThat(b.getDiscount()).as("limitado a base (o que o profissional recebe)").isEqualByComparingTo("28.00");

        Campaign legacy = campaign(new BigDecimal("50.00"), null, BenefitBase.WITHOUT_FEE);
        Attendance l = attendance("30.90", null);
        service.apply(l, legacy.getId());
        assertThat(l.getDiscount()).as("servico legado, sem divisao: a base e o total").isEqualByComparingTo("30.90");
    }

    @Test
    @DisplayName("porcentagem incide sobre a base da campanha, arredondada em centavos")
    void percent() {
        Campaign with = campaign(null, new BigDecimal("10"), BenefitBase.WITH_FEE);
        Attendance a = attendance("30.90", "28.00");
        service.apply(a, with.getId());
        assertThat(a.getDiscount()).isEqualByComparingTo("3.09");

        Campaign without = campaign(null, new BigDecimal("10"), BenefitBase.WITHOUT_FEE);
        Attendance b = attendance("30.90", "28.00");
        service.apply(b, without.getId());
        assertThat(b.getDiscount()).isEqualByComparingTo("2.80");
    }

    @Test
    @DisplayName("sem cupom nada muda")
    void noCoupon() {
        Attendance a = attendance("30.90", "28.00");
        service.apply(a, null);
        assertThat(a.getCouponCampaignId()).isNull();
        assertThat(a.getDiscount()).isEqualByComparingTo("0");
        assertThat(a.getAmountDue()).isEqualByComparingTo("30.90");
    }

    @Test
    @DisplayName("fora da lista de ativos: inexistente, nao ativado, vencido, desligado, ja usado, outro tipo, outro publico, dia errado, valor zero")
    void refusals() {
        assertRefused(attendance("30.90", null), UUID.randomUUID());

        Campaign notActivated = campaign(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        when(redemptions.existsByCampaign_IdAndUserId(notActivated.getId(), clientId)).thenReturn(false);
        assertRefused(attendance("30.90", null), notActivated.getId());

        Campaign ended = campaign(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        ended.setEndsAt(OffsetDateTime.now().minusMinutes(1));
        assertRefused(attendance("30.90", null), ended.getId());

        Campaign off = campaign(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        off.setActive(false);
        assertRefused(attendance("30.90", null), off.getId());

        Campaign used = campaign(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        when(attendances.existsByClient_IdAndCouponCampaignIdAndStatusNotIn(eq(clientId), eq(used.getId()), any()))
                .thenReturn(true);
        assertRefused(attendance("30.90", null), used.getId());

        Campaign referral = campaign(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        referral.setType(CampaignType.REFERRAL_BONUS);
        assertRefused(attendance("30.90", null), referral.getId());

        Campaign forPros = campaign(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        forPros.setAudience(CampaignAudience.PROFESSIONAL);
        assertRefused(attendance("30.90", null), forPros.getId());

        Campaign otherDay = campaign(new BigDecimal("5.00"), null, BenefitBase.WITH_FEE);
        String today = OffsetDateTime.now().atZoneSameInstant(com.ospaulos.vupth.domain.shared.BusinessClock.zone())
                .getDayOfWeek().name();
        otherDay.setWeekdays(today.equals("MONDAY") ? "TUESDAY" : "MONDAY");
        assertRefused(attendance("30.90", null), otherDay.getId());

        Campaign zero = campaign(null, null, BenefitBase.WITH_FEE);
        assertRefused(attendance("30.90", null), zero.getId());
    }

    @Test
    @DisplayName("convidado (sem conta) ignora o cupom")
    void guest() {
        Attendance a = attendance("30.90", null);
        a.setClient(null);
        service.apply(a, UUID.randomUUID());
        assertThat(a.getCouponCampaignId()).isNull();
    }

    private void assertRefused(Attendance a, UUID couponId) {
        assertThatThrownBy(() -> service.apply(a, couponId))
                .isInstanceOfSatisfying(CampaignException.class, e -> assertThat(e.getCode()).isEqualTo("COUPON_NOT_ACTIVE"));
        assertThat(a.getCouponCampaignId()).isNull();
    }
}
