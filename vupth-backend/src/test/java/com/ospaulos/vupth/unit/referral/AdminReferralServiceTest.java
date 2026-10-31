package com.ospaulos.vupth.unit.referral;

import com.ospaulos.vupth.api.referral.dto.AdminReferralPayoutResponse;
import com.ospaulos.vupth.domain.campaign.model.Campaign;
import com.ospaulos.vupth.domain.campaign.model.PayoutPeriod;
import com.ospaulos.vupth.domain.campaign.repository.CampaignRepository;
import com.ospaulos.vupth.domain.payment.PixKeyType;
import com.ospaulos.vupth.domain.referral.exception.ReferralException;
import com.ospaulos.vupth.domain.referral.repository.ReferralCreditRepository;
import com.ospaulos.vupth.domain.referral.repository.ReferralPayoutRow;
import com.ospaulos.vupth.domain.referral.service.AdminReferralService;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** O "a pagar" da bonificacao por link (BAK-9, fatia 3): datas, linhas e o marcar como pago. */
class AdminReferralServiceTest {

    private final ReferralCreditRepository creditRepository = mock(ReferralCreditRepository.class);
    private final com.ospaulos.vupth.domain.campaign.repository.CouponCreditRepository couponCreditRepository =
            mock(com.ospaulos.vupth.domain.campaign.repository.CouponCreditRepository.class);
    private final CampaignRepository campaignRepository = mock(CampaignRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminReferralService service =
            new AdminReferralService(creditRepository, couponCreditRepository, campaignRepository, userRepository);

    @Test
    @DisplayName("proximo pagamento: semanal no proximo dia (hoje conta); mensal neste mes ou no proximo; sem regra, nulo")
    void nextPayoutOn() {
        LocalDate thursday = LocalDate.of(2026, 10, 15);
        assertThat(AdminReferralService.nextPayoutOn(PayoutPeriod.WEEKLY, 4, thursday)).isEqualTo(thursday);
        assertThat(AdminReferralService.nextPayoutOn(PayoutPeriod.WEEKLY, 1, thursday)).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(AdminReferralService.nextPayoutOn(PayoutPeriod.MONTHLY, 20, thursday)).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(AdminReferralService.nextPayoutOn(PayoutPeriod.MONTHLY, 15, thursday)).isEqualTo(thursday);
        assertThat(AdminReferralService.nextPayoutOn(PayoutPeriod.MONTHLY, 5, thursday)).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(AdminReferralService.nextPayoutOn(null, 5, thursday)).isNull();
        assertThat(AdminReferralService.nextPayoutOn(PayoutPeriod.WEEKLY, null, thursday)).isNull();
    }

    @Test
    @DisplayName("linhas com a campanha, a pessoa e a chave Pix; nome vazio cai no e-mail; o que nao achou fica nulo e por ultimo")
    void payouts() {
        UUID campaignId = UUID.randomUUID();
        Campaign c = new Campaign();
        c.setId(campaignId);
        c.setName("Indique");
        c.setPayoutPeriod(PayoutPeriod.MONTHLY);
        c.setPayoutDay(28);
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(c));

        UUID beto = UUID.randomUUID();
        BaseUser betoUser = new BaseUser();
        betoUser.setId(beto);
        betoUser.setFirstName("Beto");
        betoUser.setLastName("Silva");
        betoUser.setPixKeyType(PixKeyType.PHONE);
        betoUser.setPixKey("+5511987654321");
        betoUser.setPixHolderName("Beto");
        when(userRepository.findById(beto)).thenReturn(Optional.of(betoUser));

        UUID ana = UUID.randomUUID();
        BaseUser anaUser = new BaseUser();
        anaUser.setId(ana);
        anaUser.setEmail("ana@vupth.app");
        when(userRepository.findById(ana)).thenReturn(Optional.of(anaUser));

        UUID ghost = UUID.randomUUID();
        UUID ghostCampaign = UUID.randomUUID();
        when(userRepository.findById(ghost)).thenReturn(Optional.empty());
        when(campaignRepository.findById(ghostCampaign)).thenReturn(Optional.empty());

        when(creditRepository.findUnpaidPayouts()).thenReturn(List.of(
                new ReferralPayoutRow(ghostCampaign, ghost, new BigDecimal("1.00"), 1),
                new ReferralPayoutRow(campaignId, beto, new BigDecimal("10.00"), 2),
                new ReferralPayoutRow(campaignId, ana, new BigDecimal("2.00"), 1)));

        List<AdminReferralPayoutResponse> rows = service.payouts();

        assertThat(rows).extracting(AdminReferralPayoutResponse::beneficiaryName)
                .containsExactly("Beto Silva", "ana@vupth.app", null);
        AdminReferralPayoutResponse first = rows.get(0);
        assertThat(first.campaignName()).isEqualTo("Indique");
        assertThat(first.payoutPeriod()).isEqualTo("MONTHLY");
        assertThat(first.nextPayoutOn().getDayOfMonth()).isEqualTo(28);
        assertThat(first.pixKeyType()).isEqualTo("PHONE");
        assertThat(first.pixKey()).isEqualTo("+5511987654321");
        assertThat(first.amount()).isEqualByComparingTo("10.00");
        assertThat(first.credits()).isEqualTo(2);
        assertThat(rows.get(1).pixKeyType()).isNull();
        assertThat(rows.get(2).campaignName()).isNull();
        assertThat(rows.get(2).nextPayoutOn()).isNull();
    }

    @Test
    @DisplayName("marcar como pago: devolve quantos; nada em aberto, 404 REFERRAL_NOTHING_TO_PAY")
    void markPaid() {
        UUID campaignId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(creditRepository.markPaid(eq(campaignId), eq(userId), any())).thenReturn(2, 0);

        assertThat(service.markPaid(UUID.randomUUID(), campaignId, userId).paidCredits()).isEqualTo(2);
        assertThatThrownBy(() -> service.markPaid(UUID.randomUUID(), campaignId, userId))
                .isInstanceOfSatisfying(ReferralException.class,
                        e -> assertThat(e.getCode()).isEqualTo("REFERRAL_NOTHING_TO_PAY"));
    }
    @Test
    @DisplayName("repasse automatico (DEC-132): desligado por padrao 409 e nada transferido; ligado, transfere pelo Pix do Asaas e registra o pago como no manual; sem chave Pix 422")
    void payAuto() {
        UUID campaignId = UUID.randomUUID();
        UUID beneficiary = UUID.randomUUID();
        Campaign c = new Campaign();
        c.setId(campaignId);
        c.setName("Indique");
        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(c));
        BaseUser user = new BaseUser();
        user.setId(beneficiary);
        user.setFirstName("Beto");
        user.setPixKeyType(PixKeyType.RANDOM);
        user.setPixKey("123e4567-e89b-12d3-a456-426614174000");
        when(userRepository.findById(beneficiary)).thenReturn(Optional.of(user));
        when(creditRepository.findUnpaidPayouts())
                .thenReturn(List.of(new ReferralPayoutRow(campaignId, beneficiary, new BigDecimal("12.50"), 2)));
        when(couponCreditRepository.findUnpaidPayouts()).thenReturn(List.of());
        when(creditRepository.markPaid(eq(campaignId), eq(beneficiary), any())).thenReturn(2);
        when(couponCreditRepository.markPaid(eq(campaignId), eq(beneficiary), any())).thenReturn(0);
        var client = mock(com.ospaulos.vupth.infrastructure.asaas.AsaasClient.class);
        when(client.transferPix(any(), any(), any(), any())).thenReturn("tra_1");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "asaasClient", client);

        assertThatThrownBy(() -> service.payAuto(UUID.randomUUID(), campaignId, beneficiary))
                .hasMessageContaining("desligado");
        verify(client, never()).transferPix(any(), any(), any(), any());
        verify(creditRepository, never()).markPaid(any(), any(), any());

        org.springframework.test.util.ReflectionTestUtils.setField(service, "payoutAuto", true);
        var paid = service.payAuto(UUID.randomUUID(), campaignId, beneficiary);
        assertThat(paid.transferId()).isEqualTo("tra_1");
        assertThat(paid.amount()).isEqualByComparingTo("12.50");
        assertThat(paid.paid().paidCredits()).isEqualTo(2);
        verify(client).transferPix(eq(new BigDecimal("12.50")), eq("123e4567-e89b-12d3-a456-426614174000"), eq("EVP"), any());

        user.setPixKey(null);
        assertThatThrownBy(() -> service.payAuto(UUID.randomUUID(), campaignId, beneficiary)).hasMessageContaining("chave Pix");
        assertThatThrownBy(() -> service.payAuto(UUID.randomUUID(), campaignId, UUID.randomUUID()))
                .isInstanceOf(ReferralException.class);
    }
}
