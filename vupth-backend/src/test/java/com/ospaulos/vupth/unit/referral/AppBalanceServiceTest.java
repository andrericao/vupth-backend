package com.ospaulos.vupth.unit.referral;

import com.ospaulos.vupth.api.referral.dto.AdminReferralPayoutResponse;
import com.ospaulos.vupth.domain.referral.service.AdminReferralService;
import com.ospaulos.vupth.domain.referral.service.AppBalanceService;
import com.ospaulos.vupth.domain.referral.service.AppBalanceService.Balance;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** saldo-do-app-e-bonificacao (DEC-120): o que se deve por pessoa e no total, o saldo do Asaas e a diferenca a depositar. */
class AppBalanceServiceTest {

    private final AdminReferralService referrals = mock(AdminReferralService.class);
    private final AsaasClient asaas = mock(AsaasClient.class);
    private final AppBalanceService service = new AppBalanceService(referrals, asaas);

    private final UUID ana = UUID.randomUUID();
    private final UUID beto = UUID.randomUUID();

    private AdminReferralPayoutResponse row(UUID campaign, UUID person, String name, String amount, long credits) {
        return new AdminReferralPayoutResponse(campaign, "C", null, null, null, person, name, null, null, null,
                new BigDecimal(amount), credits);
    }

    private void owes() {
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        when(referrals.payouts()).thenReturn(List.of(
                row(c1, ana, "Ana", "10.00", 2), row(c2, ana, "Ana", "5.50", 1), row(c1, beto, "Beto", "30.00", 3)));
        when(referrals.pendingRelease()).thenReturn(List.of(row(c1, beto, "Beto", "7.00", 1)));
    }

    @Test
    @DisplayName("soma a bonificacao por pessoa (varias campanhas), o devedor total e o que espera liberacao; maior primeiro")
    void owedByPerson() {
        owes();
        when(asaas.configured()).thenReturn(false);

        Balance b = service.balance();

        assertThat(b.owedTotal()).isEqualByComparingTo("45.50");
        assertThat(b.pendingReleaseTotal()).isEqualByComparingTo("7.00");
        assertThat(b.beneficiaries()).hasSize(2);
        assertThat(b.beneficiaries().get(0).beneficiaryName()).isEqualTo("Beto");
        assertThat(b.beneficiaries().get(1).amount()).isEqualByComparingTo("15.50");
        assertThat(b.beneficiaries().get(1).credits()).isEqualTo(3);
    }

    @Test
    @DisplayName("sem conexao com o Asaas: so o devedor, sem saldo nem diferenca")
    void withoutAsaas() {
        owes();
        when(asaas.configured()).thenReturn(false);

        Balance b = service.balance();

        assertThat(b.asaasConnected()).isFalse();
        assertThat(b.appBalance()).isNull();
        assertThat(b.depositNeeded()).isNull();
    }

    @Test
    @DisplayName("com o Asaas: saldo menor que o devedor → a diferenca a depositar; saldo que cobre → zero")
    void depositNeeded() {
        owes();
        when(asaas.configured()).thenReturn(true);
        when(asaas.balance()).thenReturn(new BigDecimal("20.00"));
        Balance short_ = service.balance();
        assertThat(short_.asaasConnected()).isTrue();
        assertThat(short_.appBalance()).isEqualByComparingTo("20.00");
        assertThat(short_.depositNeeded()).isEqualByComparingTo("25.50");

        when(asaas.balance()).thenReturn(new BigDecimal("100.00"));
        assertThat(service.balance().depositNeeded()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("Asaas fora do ar: conectado mas sem saldo, com o codigo do erro; o devedor segue")
    void asaasFailure() {
        owes();
        when(asaas.configured()).thenReturn(true);
        when(asaas.balance()).thenThrow(new AsaasException(HttpStatus.BAD_GATEWAY, "ASAAS_REJECTED", "recusou"));

        Balance b = service.balance();

        assertThat(b.asaasConnected()).isTrue();
        assertThat(b.appBalance()).isNull();
        assertThat(b.asaasError()).isEqualTo("ASAAS_REJECTED");
        assertThat(b.owedTotal()).isEqualByComparingTo("45.50");
    }
}
