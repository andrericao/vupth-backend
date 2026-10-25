package com.ospaulos.vupth.unit.payment;

import com.ospaulos.vupth.domain.payment.model.PaymentAccount;
import com.ospaulos.vupth.domain.payment.repository.PaymentAccountRepository;
import com.ospaulos.vupth.domain.payment.service.PaymentAccountService;
import com.ospaulos.vupth.domain.payment.service.PaymentAccountService.State;
import com.ospaulos.vupth.domain.user.model.BaseUser;
import com.ospaulos.vupth.domain.user.repository.UserRepository;
import com.ospaulos.vupth.infrastructure.asaas.AsaasClient;
import com.ospaulos.vupth.infrastructure.asaas.AsaasException;
import com.ospaulos.vupth.infrastructure.asaas.AsaasProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** gateway-de-pagamento, fatia 1: a subconta de teste do profissional, com o aceite marcado como teste. */
class PaymentAccountServiceTest {

    private static final String SANDBOX = "https://api-sandbox.asaas.com/v3";
    private final PaymentAccountRepository accounts = mock(PaymentAccountRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final AsaasClient client = mock(AsaasClient.class);
    private final UUID userId = UUID.randomUUID();

    private PaymentAccountService service(String url) {
        return new PaymentAccountService(accounts, users, client, new AsaasProperties("k", url, ""));
    }

    private BaseUser user() {
        BaseUser u = new BaseUser();
        u.setId(userId);
        u.setFirstName("Beto");
        when(users.findById(userId)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    @DisplayName("no sandbox abre a subconta de teste com dados ficticios validos e guarda o aceite (versao, data, IP, test=true) sem a chave de API")
    void opensTestSubaccount() {
        user();
        when(accounts.findById(userId)).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(client.createSubaccount(any())).thenReturn(new AsaasClient.Subaccount("acc_1", "wallet_1"));

        State state = service(SANDBOX).open(userId, "2026-10-24-rascunho", "203.0.113.9");

        assertThat(state.status()).isEqualTo("ACTIVE");
        assertThat(state.mode()).isEqualTo("SANDBOX");
        assertThat(state.test()).isTrue();
        assertThat(state.acceptVersion()).isEqualTo("2026-10-24-rascunho");
        ArgumentCaptor<PaymentAccount> saved = ArgumentCaptor.forClass(PaymentAccount.class);
        verify(accounts).save(saved.capture());
        assertThat(saved.getValue().getWalletId()).isEqualTo("wallet_1");
        assertThat(saved.getValue().getAcceptedIp()).isEqualTo("203.0.113.9");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(client).createSubaccount(body.capture());
        assertThat(body.getValue().get("name")).isEqualTo("Beto Teste VUPTH");
        assertThat((String) body.getValue().get("email")).startsWith("teste+").endsWith("@vupth.test");
        assertThat((String) body.getValue().get("cpfCnpj")).matches("\\d{11}");
    }

    @Test
    @DisplayName("abrir de novo devolve a mesma conta sem criar outra; no modo real a abertura fica recusada (409)")
    void idempotentAndRealRefused() {
        PaymentAccount existing = new PaymentAccount(userId, "acc_1", "wallet_1", "SANDBOX", "v1", java.time.OffsetDateTime.now(), "ip", true);
        when(accounts.findById(userId)).thenReturn(Optional.of(existing));
        assertThat(service(SANDBOX).open(userId, "v2", "ip").acceptVersion()).isEqualTo("v1");
        verify(client, never()).createSubaccount(any());

        when(accounts.findById(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service("https://api.asaas.com/v3").open(userId, "v1", "ip"))
                .isInstanceOfSatisfying(AsaasException.class, e -> assertThat(e.getCode()).isEqualTo("PAYMENT_ACCOUNT_REAL_NOT_READY"));
        verify(client, never()).createSubaccount(any());
    }

    @Test
    @DisplayName("a carteira da divisao so vale se a conta esta ativa e no mesmo modo da cobranca")
    void walletForMode() {
        PaymentAccount sandbox = new PaymentAccount(userId, "acc_1", "wallet_1", "SANDBOX", "v1", java.time.OffsetDateTime.now(), "ip", true);
        when(accounts.findById(userId)).thenReturn(Optional.of(sandbox));
        PaymentAccountService s = service(SANDBOX);
        assertThat(s.walletFor(userId, "SANDBOX")).contains("wallet_1");
        assertThat(s.walletFor(userId, "REAL")).as("cobranca real nao usa subconta de teste").isEmpty();
        when(accounts.findById(userId)).thenReturn(Optional.empty());
        assertThat(s.walletFor(userId, "SANDBOX")).isEmpty();
        assertThat(s.state(userId)).isEmpty();
    }
}
